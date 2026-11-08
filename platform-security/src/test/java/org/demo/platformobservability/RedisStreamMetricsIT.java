package org.demo.platformobservability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisStreamMetricsIT {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connection;static StringRedisTemplate redis;
    @BeforeAll static void connect() { connection=factory(null);redis=template(connection); }
    @AfterAll static void disconnect() { connection.destroy(); }
    static LettuceConnectionFactory factory(String username) {
        var config=new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));
        if(username!=null) {config.setUsername(username);config.setPassword("fixture-only");}
        var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).shutdownTimeout(Duration.ofMillis(100)).build();
        var factory=new LettuceConnectionFactory(config,client);factory.afterPropertiesSet();factory.start();return factory;
    }
    static StringRedisTemplate template(LettuceConnectionFactory factory) {var t=new StringRedisTemplate(factory);t.afterPropertiesSet();return t;}
    static String stream() {
        String stream="metric-"+UUID.randomUUID();
        for(String id:List.of("10-0","20-0","30-0"))redis.opsForStream().add(StreamRecords.newRecord().ofMap(Map.of("private-payload","fixture-content")).withStreamKey(stream).withId(RecordId.of(id)));
        return stream;
    }
    record Probe(RedisStreamMetrics metrics,SimpleMeterRegistry registry) implements AutoCloseable {
        double value(String name) {return registry.get("platform.redis.stream."+name).gauge().value();}
        public void close() {registry.close();}
    }
    static Probe probe(StringRedisTemplate client,String stream,String group,RedisStreamMetrics.Recipient recipient) {
        var registry=new SimpleMeterRegistry();var m=new RedisStreamMetrics(client,stream,group,recipient);m.bindTo(registry);return new Probe(m,registry);
    }
    @Test void pendingAndLagFollowEachGroupWithoutChangingTheStreamOrAcknowledging() {
        String stream=stream();redis.opsForStream().createGroup(stream,ReadOffset.from("0-0"),"billing-fixture");
        redis.opsForStream().createGroup(stream,ReadOffset.from("0-0"),"notification-fixture");
        var records=redis.opsForStream().read(Consumer.from("billing-fixture","fixture-reader"),StreamReadOptions.empty().count(1),StreamOffset.create(stream,ReadOffset.lastConsumed()));
        try(var billing=probe(redis,stream,"billing-fixture",RedisStreamMetrics.Recipient.BILLING);
            var notification=probe(redis,stream,"notification-fixture",RedisStreamMetrics.Recipient.NOTIFICATION)) {
            byte[] before=redis.dump(stream);
            for(int i=0;i<3;i++) {billing.metrics().collect();notification.metrics().collect();}
            assertEquals(1,billing.value("pending"));assertEquals(2,billing.value("lag"));
            assertEquals(0,notification.value("pending"));assertEquals(3,notification.value("lag"));
            assertArrayEquals(before,redis.dump(stream));
            redis.opsForStream().acknowledge(stream,"billing-fixture",records.getFirst().getId());billing.metrics().collect();
            assertEquals(0,billing.value("pending"));assertEquals(2,billing.value("lag"));
        }
    }
    @Test void unknownRedisLagIsNotZeroAndCanRecoverAfterNormalConsumption() {
        String stream=stream();redis.opsForStream().createGroup(stream,ReadOffset.from("15-0"),"arbitrary-offset");
        try(var p=probe(redis,stream,"arbitrary-offset",RedisStreamMetrics.Recipient.BILLING)) {
            p.metrics().collect();assertEquals(1,p.value("collector.up"));assertEquals(0,p.value("pending"));
            assertTrue(Double.isNaN(p.value("lag")));assertEquals(0,p.value("lag.known"));
            redis.opsForStream().read(Consumer.from("arbitrary-offset","fixture-reader"),StreamReadOptions.empty().count(20),StreamOffset.create(stream,ReadOffset.lastConsumed()));
            p.metrics().collect();assertEquals(2,p.value("pending"));assertEquals(0,p.value("lag"));assertEquals(1,p.value("lag.known"));
        }
    }
    @Test void missingStreamsAndGroupsAreNotCreatedByInspection() {
        String missing="missing-"+UUID.randomUUID();
        try(var p=probe(redis,missing,"missing-group",RedisStreamMetrics.Recipient.BILLING)) {
            p.metrics().collect();assertEquals(0,p.value("collector.up"));assertTrue(Double.isNaN(p.value("pending")));assertFalse(redis.hasKey(missing));
        }
        String stream=stream();redis.opsForStream().createGroup(stream,ReadOffset.from("0-0"),"different");
        try(var p=probe(redis,stream,"missing-group",RedisStreamMetrics.Recipient.BILLING)) {
            byte[] before=redis.dump(stream);p.metrics().collect();
            assertEquals(1,p.value("collector.up"));assertEquals(0,p.value("group.present"));assertTrue(Double.isNaN(p.value("pending")));
            assertArrayEquals(before,redis.dump(stream));
            redis.opsForStream().createGroup(stream,ReadOffset.from("0-0"),"missing-group");p.metrics().collect();
            assertEquals(1,p.value("group.present"));assertEquals(3,p.value("lag"));
        }
    }
    @Test void aRealRedisOutageInvalidatesCountsAndRestoresAfterRecovery() throws Exception {
        String stream=stream();redis.opsForStream().createGroup(stream,ReadOffset.from("0-0"),"fixture");
        try(var p=probe(redis,stream,"fixture",RedisStreamMetrics.Recipient.NOTIFICATION)) {
            p.metrics().collect();assertEquals(1,p.value("collector.up"));double last=p.value("collector.last.success.timestamp.seconds");
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {p.metrics().collect();assertEquals(0,p.value("collector.up"));assertTrue(Double.isNaN(p.value("pending")));assertTrue(Double.isNaN(p.value("lag")));assertEquals(last,p.value("collector.last.success.timestamp.seconds"));}
            finally {REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();}
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            do {p.metrics().collect();if(p.value("collector.up")==1)break;Thread.sleep(50);} while(System.nanoTime()<deadline);
            assertEquals(1,p.value("collector.up"));assertEquals(3,p.value("lag"));assertEquals(0,p.value("pending"));
        }
    }
    @Test void collectionWorksWithOnlyGroupMetadataPermissionAndCannotReadPayloads() {
        String stream=stream();redis.opsForStream().createGroup(stream,ReadOffset.from("0-0"),"fixture");
        String user="metrics_"+UUID.randomUUID().toString().replace("-","");
        String[] args={"SETUSER",user,"on",">fixture-only","~"+stream,"+@connection","+xinfo|groups"};
        redis.execute((RedisCallback<Object>) c->c.execute("ACL",Arrays.stream(args).map(s->s.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new)));
        var factory=factory(user);
        try {
            var restricted=template(factory);
            try(var p=probe(restricted,stream,"fixture",RedisStreamMetrics.Recipient.BILLING)) {
                p.metrics().collect();assertEquals(1,p.value("collector.up"));assertEquals(3,p.value("lag"));
                assertThrows(DataAccessException.class,()->restricted.opsForStream().range(stream,org.springframework.data.domain.Range.unbounded()));
                assertThrows(DataAccessException.class,()->restricted.opsForStream().acknowledge(stream,"fixture","10-0"));
            }
        } finally {factory.destroy();}
    }
}
