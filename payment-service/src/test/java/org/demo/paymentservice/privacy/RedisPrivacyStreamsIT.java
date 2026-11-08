package org.demo.paymentservice.privacy;

import org.demo.platformevents.*;
import org.demo.platformsecurity.privacy.FinancePreparationAdapter;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RedisPrivacyStreamsIT {
    @Container static final GenericContainer<?> DB=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;static StringRedisTemplate redis;
    final String stream="payment-fixture",groups="billing-service,notification-service";final UUID request=UUID.randomUUID();
    @BeforeAll static void start(){factory=new LettuceConnectionFactory(DB.getHost(),DB.getMappedPort(6379));factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();}
    @AfterAll static void stop(){factory.destroy();}
    @BeforeEach void reset(){try(var connection=factory.getConnection()){connection.serverCommands().flushDb();}}
    Map<String,String> fields(long order){return Map.of("orderId",Long.toString(order),"eventType","PAYMENT_SUCCEEDED","recipient","owner@fixture.example","amount","10.00","eventId","payment-fixture-"+order);}
    String append(long order){return redis.opsForStream().add(StreamRecords.newRecord().ofMap(fields(order)).withStreamKey(stream)).getValue();}
    void group(String group){redis.execute(new DefaultRedisScript<>("return redis.call('XGROUP','CREATE',KEYS[1],ARGV[1],'0-0','MKSTREAM')",String.class),List.of(stream),group);}
    void read(String group){redis.opsForStream().read(org.springframework.data.redis.connection.stream.Consumer.from(group,"fixture"),StreamReadOptions.empty().count(1000),StreamOffset.create(stream,ReadOffset.lastConsumed()));}
    void ack(String group,String id){redis.opsForStream().acknowledge(stream,group,RecordId.of(id));}
    FinancePreparationAdapter.Result prepare(String cursor){return new RedisPrivacyStreams(redis).prepare(stream,groups,71,request,cursor);}
    @Test void unreadAndPendingRecordsAndEveryConsumerGroupAreProtected(){
        String own=append(71),other=append(72);for(String group:groups.split(","))group(group);
        assertThat(prepare("0-0").code()).isEqualTo("WAITING_STREAM_ACK");assertThat(redis.opsForStream().size(stream)).isEqualTo(2);
        read("billing-service");ack("billing-service",own);ack("billing-service",other);
        assertThat(prepare("0-0").state()).isEqualTo("WAIT");read("notification-service");
        assertThat(prepare("0-0").state()).isEqualTo("WAIT");ack("notification-service",own);
        assertThat(prepare("0-0").state()).isEqualTo("PREPARED");assertThat(redis.opsForStream().size(stream)).isEqualTo(1);
        assertThat(redis.opsForStream().pending(stream,"notification-service").getTotalPendingMessages()).isEqualTo(1);
        assertThat(redis.opsForStream().range(stream,org.springframework.data.domain.Range.closed(other,other))).hasSize(1);
    }
    @Test void missingOrUndeclaredGroupsNeverAuthorizeDeletion(){String own=append(71);var cleaner=new RedisPrivacyStreams(redis);assertThat(cleaner.prepare(stream,"",71,request,"0-0").code()).isEqualTo("WAITING_STREAM_POLICY");assertThat(cleaner.prepare(stream,"missing",71,request,"0-0").code()).isEqualTo("WAITING_STREAM_POLICY");assertThat(redis.opsForStream().groups(stream)).isEmpty();assertThat(redis.opsForStream().size(stream)).isEqualTo(1);assertThatThrownBy(()->cleaner.prepare(stream,groups,71,UUID.randomUUID(),"0-0")).isInstanceOf(RuntimeException.class);}
    @Test void scanResumesInBoundedPagesAgainstTheCapturedHighWater(){for(int n=0;n<205;n++)append(72);var first=prepare("0-0");assertThat(first.state()).isEqualTo("MORE");var second=new RedisPrivacyStreams(redis).prepare(stream,groups,71,request,first.cursor());assertThat(second.state()).isEqualTo("MORE");assertThat(prepare(second.cursor()).state()).isEqualTo("PREPARED");assertThat(redis.opsForStream().size(stream)).isEqualTo(205);assertThat(RedisPrivacyStreams.publish(redis,stream,71,fields(71))).isTrue();assertThat(redis.opsForStream().size(stream)).isEqualTo(205);assertThat(redis.getExpire("privacy:stream:"+stream+":order:71")).isEqualTo(-1);}
    @Test void lateLegacyPayloadDisappearsOnlyAfterBothCommittedConsumersAck(){for(String group:groups.split(","))group(group);assertThat(prepare("0-0").state()).isEqualTo("PREPARED");String id=append(71);read("billing-service");new RedisEventStreams(redis,stream,"billing-service","fixture",1000,604800000,groups).acknowledge(RecordId.of(id));assertThat(redis.opsForStream().size(stream)).isEqualTo(1);read("notification-service");new RedisEventStreams(redis,stream,"notification-service","fixture",1000,604800000,groups).acknowledge(RecordId.of(id));assertThat(redis.opsForStream().size(stream)).isZero();}
    @Test void requiredFinancialMetadataCanPublishButPrivateFieldsCannot(){assertThat(prepare("0-0").state()).isEqualTo("PREPARED");var minimal=new LinkedHashMap<String,String>(Map.of("orderId","71","eventId","invoice-corrected-1","eventType","INVOICE_CORRECTED","privacySuppressed","true","correctionGross","-1.00"));assertThat(RedisPrivacyStreams.publish(redis,stream,71,minimal)).isTrue();assertThat(redis.opsForStream().size(stream)).isEqualTo(1);minimal.put("recipient","private@fixture.example");assertThatThrownBy(()->RedisPrivacyStreams.publish(redis,stream,71,minimal)).isInstanceOf(RuntimeException.class);assertThat(redis.opsForStream().size(stream)).isEqualTo(1);}
    @Test void concurrentFenceAndPublicationNeverCreateAnUnfencedFuturePrivateRecord()throws Exception{
        for(int n=0;n<10;n++){reset();for(String group:groups.split(","))group(group);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var gate=new java.util.concurrent.CountDownLatch(1);var producer=pool.submit(()->{gate.await();return RedisPrivacyStreams.publish(redis,stream,71,fields(71));});var barrier=pool.submit(()->{gate.await();return prepare("0-0");});gate.countDown();assertThat(producer.get()).isTrue();barrier.get();}
            var records=redis.opsForStream().range(stream,org.springframework.data.domain.Range.unbounded());for(String group:groups.split(",")){read(group);for(var record:records)ack(group,record.getId().getValue());}assertThat(prepare("0-0").state()).isEqualTo("PREPARED");assertThat(redis.opsForStream().size(stream)).isZero();
        }
    }
}
