package org.demo.billingservice.messaging.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.billingservice.messaging.InvoiceOutboxEvent;
import org.demo.billingservice.messaging.InvoiceStreamPublisher;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.demo.billingservice.messaging.retention.InvoiceStreamRetentionRepository.*;

@Testcontainers
class InvoiceStreamRetentionRedisIT {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final String KEY="invoice-retention-fixture";
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private InvoiceStreamRetentionRepository repository;
    private InvoiceStreamRetentionWorker activeWorker;
    @BeforeAll static void connect() {
        var config=new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));
        factory=new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);
        try(var connection=factory.getConnection()) {
            String version=connection.serverCommands().info("server").getProperty("redis_version");assertThat(version).startsWith("7.");
            System.out.println("A43.14 Redis fixture version: "+version);
        }
    }
    @AfterAll static void disconnect() {if(factory!=null)factory.destroy();}
    @BeforeEach void cleanOwnedFixture() {
        // This client is constructed only from this test's ephemeral container host and mapped port.
        try(var connection=factory.getConnection()){connection.serverCommands().flushDb();connection.serverCommands().setConfig("stream-node-max-entries","100");}
        repository=new InvoiceStreamRetentionRepository(redis,KEY);
    }
    private void add(String... ids) {for(String id:ids)redis.opsForStream().add(StreamRecords.newRecord().ofMap(Map.of("fixture","private-fixture-body")).withStreamKey(KEY).withId(RecordId.of(id)));}
    private void group(String name) {redis.opsForStream().createGroup(KEY,ReadOffset.from("0-0"),name);}
    private List<RecordId> read(String group,int count) {
        var records=redis.opsForStream().read(org.springframework.data.redis.connection.stream.Consumer.from(group,"fixture"),
                StreamReadOptions.empty().count(count),StreamOffset.create(KEY,ReadOffset.lastConsumed()));
        return records==null?List.of():records.stream().map(MapRecord::getId).toList();
    }
    private void ack(String group,List<RecordId> ids) {if(!ids.isEmpty())redis.opsForStream().acknowledge(KEY,group,ids.toArray(RecordId[]::new));}
    private List<String> ids() {return redis.opsForStream().range(KEY,Range.unbounded()).stream().map(r->r.getId().getValue()).toList();}
    private InvoiceStreamRetentionRepository.Snapshot run(boolean trim,int size,List<String> required,boolean allow) {return repository.run(100,trim,size,required,allow);}
    private double gauge(SimpleMeterRegistry r,String key){return r.get("billing.invoice.stream.retention."+key).gauge().value();}

    @Test void missingStreamIsObservedWithoutCreatingKeysOrConsumers() {
        var result=run(true,2,List.of(),true);assertThat(result.blocked()).isEqualTo(MISSING);assertThat(result.length()).isZero();assertThat(redis.hasKey(KEY)).isFalse();
    }
    @Test void observationAndAbsentRecipientPolicyNeverDeleteEvenWhenAnActualGroupExists() {
        add("10-0","20-0","30-0");var observed=run(false,2,List.of(),true);assertThat(observed.eligible()).isTrue();assertThat(observed.old()).isTrue();assertThat(observed.removed()).isZero();
        assertThat(run(true,2,List.of(),false).blocked()).isEqualTo(NO_GROUP_POLICY);group("a");ack("a",read("a",10));
        assertThat(run(true,2,List.of(),false).blocked()).isEqualTo(NO_GROUP_POLICY);assertThat(ids()).containsExactly("10-0","20-0","30-0");
    }
    @Test void explicitNoGroupPolicyDeletesOnlyStrictlyOlderMilliseconds() {
        add("99-0","100-0","100-1","101-0");var result=run(true,10,List.of(),true);
        assertThat(result.removed()).isEqualTo(1);assertThat(result.old()).isFalse();assertThat(result.eligible()).isFalse();assertThat(ids()).containsExactly("100-0","100-1","101-0");
        assertThat(repository.run(1000,true,10,List.of(),true).removed()).isEqualTo(3);assertThat(redis.hasKey(KEY)).isTrue();
        var empty=repository.run(1000,true,10,List.of(),true);assertThat(empty.length()).isZero();assertThat(empty.oldestId()).isEmpty();
        assertThatThrownBy(()->add("100-2")).isInstanceOf(DataAccessException.class);
    }
    @Test void everyRequiredGroupMustExistAndCatchUpBeforeOldEntriesCanBeDeleted() {
        add("10-0","20-0","30-0");group("a");ack("a",read("a",10));
        assertThat(run(true,10,List.of("a","b"),true).blocked()).isEqualTo(REQUIRED_GROUP_MISSING);
        group("b");assertThat(run(true,10,List.of("a","b"),true).removed()).isZero();ack("b",read("b",10));
        assertThat(run(true,10,List.of("a","b"),false).removed()).isEqualTo(2);assertThat(ids()).containsExactly("30-0");
    }
    @Test void pendingAndUnreadRecordsRemainProtectedUntilEveryGroupAcknowledges() {
        add("10-0","20-0","30-0","40-0");group("a");group("b");ack("a",read("a",10));var partial=read("b",2);
        assertThat(run(true,10,List.of("a","b"),false).removed()).isZero();ack("b",partial.subList(0,1));
        assertThat(run(true,10,List.of("a","b"),false).removed()).isEqualTo(1);assertThat(ids()).containsExactly("20-0","30-0","40-0");
        ack("b",partial.subList(1,2));assertThat(run(true,10,List.of("a","b"),false).blocked()).isEqualTo(PROTECTED_FRONTIER);
        ack("b",read("b",10));assertThat(run(true,10,List.of("a","b"),false).removed()).isEqualTo(2);assertThat(ids()).containsExactly("40-0");
        assertThat(redis.opsForStream().pending(KEY,"b").getTotalPendingMessages()).isZero();
    }
    @Test void additionalUndeclaredConsumerGroupsAreProtectedToo() {
        add("10-0","20-0","30-0");group("expected");ack("expected",read("expected",10));group("extra");
        assertThat(run(true,10,List.of("expected"),false).removed()).isZero();var pending=read("extra",10);
        assertThat(run(true,10,List.of("expected"),false).removed()).isZero();ack("extra",pending);
        assertThat(run(true,10,List.of("expected"),false).removed()).isEqualTo(2);assertThat(ids()).containsExactly("30-0");
    }
    @Test void tooManyGroupsBlockDeletionBeforeExpandingTheirPendingLists() {
        add("10-0");for(int i=0;i<33;i++)group("g"+i);var result=run(true,10,List.of(),true);
        assertThat(result.blocked()).isEqualTo(TOO_MANY_GROUPS);assertThat(result.groups()).isEqualTo(33);assertThat(result.removed()).isZero();assertThat(ids()).containsExactly("10-0");
    }
    @Test void decimalIdComparisonPreservesAdjacentLargeSequencesAndUnsignedFutureIds() {
        add("1-9007199254740992","1-9007199254740993","1-9007199254740994");group("a");var delivered=read("a",2);ack("a",delivered.subList(0,1));
        var result=run(true,10,List.of("a"),false);assertThat(result.removed()).isEqualTo(1);assertThat(result.oldestId()).isEqualTo("1-9007199254740993");
        assertThat(ids()).containsExactly("1-9007199254740993","1-9007199254740994");
        String future="18446744073709551615-18446744073709551615";
        redis.opsForStream().add(StreamRecords.newRecord().ofMap(Map.of("fixture","future")).withStreamKey("future-fixture").withId(RecordId.of(future)));
        var futureResult=new InvoiceStreamRetentionRepository(redis,"future-fixture").run(Long.MAX_VALUE,true,10,List.of(),true);
        assertThat(futureResult.removed()).isZero();assertThat(futureResult.oldestId()).isEqualTo(future);assertThat(futureResult.oldestAgeSeconds(1_800_000_000_000L)).isZero();
    }
    @Test void batchSizeIsAHardDeletionLimitEvenWithLargeMacroNodes() {
        try(var connection=factory.getConnection()){connection.serverCommands().setConfig("stream-node-max-entries","10000");}
        for(int i=1;i<=50;i++)add(i+"-0");
        for(int i=0;i<10;i++)assertThat(run(true,1,List.of(),true).removed()).isEqualTo(1);
        assertThat(ids()).hasSize(40).first().isEqualTo("11-0");
    }
    @Test void parallelCollectorsAndTheRealPublisherKeepFreshEventsAndRespectBudgets() throws Exception {
        for(int i=1;i<=20;i++)add(i+"-0");var start=new CountDownLatch(1);
        var publisher=new InvoiceStreamPublisher(redis,KEY);long removed;
        try(var pool=Executors.newFixedThreadPool(3)) {
            var a=pool.submit(()->{start.await();return run(true,2,List.of(),true).removed();});var b=pool.submit(()->{start.await();return run(true,2,List.of(),true).removed();});
            var fresh=pool.submit(()->{start.await();return publisher.publish(new InvoiceOutboxEvent("fixture-published",Map.of("eventType","INVOICE_ISSUED","invoiceId","1","orderId","1")));});
            start.countDown();removed=a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS);assertThat(fresh.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(removed).isEqualTo(4);assertThat(ids()).hasSize(17);for(int i=0;i<10;i++)run(true,3,List.of(),true);
        assertThat(ids()).hasSize(1);assertThat(redis.opsForStream().range(KEY,Range.unbounded()).getFirst().getValue()).containsEntry("eventId","fixture-published");
    }
    @Test void concurrentReadAndAcknowledgementCannotLosePendingOrUnreadMessages() throws Exception {
        for(int i=1;i<=50;i++)add(i+"-0");group("g");var start=new CountDownLatch(1);List<RecordId> delivered;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reader=pool.submit(()->{start.await();return read("g",20);});var cleaner=pool.submit(()->{start.await();return run(true,100,List.of("g"),false);});
            start.countDown();delivered=reader.get(5,TimeUnit.SECONDS);assertThat(cleaner.get(5,TimeUnit.SECONDS).removed()).isZero();
        }
        assertThat(delivered).hasSize(20);assertThat(ids()).hasSize(50);assertThat(redis.opsForStream().pending(KEY,"g").getTotalPendingMessages()).isEqualTo(20);
        var next=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ack=pool.submit(()->{next.await();ack("g",delivered);return true;});var cleaner=pool.submit(()->{next.await();return run(true,100,List.of("g"),false);});
            next.countDown();ack.get(5,TimeUnit.SECONDS);assertThat(cleaner.get(5,TimeUnit.SECONDS).removed()).isBetween(0L,19L);
        }
        run(true,100,List.of("g"),false);assertThat(ids()).hasSize(31).contains("20-0","21-0","50-0");
    }
    @Test void wrongTypeIsPreservedAndTheActualWorkerRecoversWithoutPublishingExceptionDetails() {
        redis.opsForValue().set(KEY,"fixture-sensitive-value");assertThatThrownBy(()->run(true,2,List.of(),true)).isInstanceOf(DataAccessException.class);
        var registry=new SimpleMeterRegistry();activeWorker=new InvoiceStreamRetentionWorker(repository,
                InvoiceStreamRetentionWorkerTest.properties(InvoiceStreamRetentionProperties.Mode.OBSERVE,4),registry,Clock.fixed(InvoiceStreamRetentionWorkerTest.NOW,ZoneOffset.UTC));
        activeWorker.cleanup();assertThat(gauge(registry,"length")).isEqualTo(-1);assertThat(redis.opsForValue().get(KEY)).isEqualTo("fixture-sensitive-value");
        redis.delete(KEY);add("10-0");activeWorker.cleanup();assertThat(gauge(registry,"length")).isEqualTo(1);
        assertThat(registry.get("billing.invoice.stream.retention.runs").tag("outcome","failed").counter().count()).isEqualTo(1);
    }
}
