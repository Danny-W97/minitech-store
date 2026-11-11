package org.demo.platformobservability;

import org.demo.platformevents.RedisEventStreams;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisCorrelationIT {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connection;static StringRedisTemplate redis;
    @BeforeAll static void connect() {
        connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
    }
    @AfterAll static void disconnect() {connection.destroy();}
    @AfterEach void clearContext() {MDC.clear();}
    @Test void freshAndClaimedRecordsHaveTheirOwnContextWithoutChangingPayloadOrAcknowledging() throws Exception {
        String stream="scope-"+UUID.randomUUID(),group="fixture";
        var first=Map.of("correlationId","record-first","value","one");
        var second=Map.of("correlationId","bad\nvalue","value","two");
        redis.opsForStream().add(StreamRecords.newRecord().ofMap(first).withStreamKey(stream));
        redis.opsForStream().add(StreamRecords.newRecord().ofMap(second).withStreamKey(stream));
        var reader=new RedisEventStreams(redis,stream,group,"fixture",1000,60000,"");
        var seen=new ArrayList<Map<String,String>>();
        java.util.function.Consumer<org.springframework.data.redis.connection.stream.MapRecord<String,?,?>> handler=record->{
            String id=MDC.get(CorrelationIdContext.MDC_KEY);
            if("one".equals(record.getValue().get("value"))) assertEquals("record-first",id);
            else assertDoesNotThrow(()->UUID.fromString(id));
            var copy=new LinkedHashMap<String,String>();record.getValue().forEach((k,v)->copy.put(k.toString(),v.toString()));seen.add(copy);
        };
        MDC.put(CorrelationIdContext.MDC_KEY,"poll-caller");reader.poll(handler);
        assertEquals(List.of(first,second),seen);assertEquals("poll-caller",MDC.get(CorrelationIdContext.MDC_KEY));
        long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while(seen.size()<4) {assertTrue(System.nanoTime()<deadline);Thread.sleep(100);reader.poll(handler);}
        assertEquals(List.of(first,second,first,second),seen);assertEquals("poll-caller",MDC.get(CorrelationIdContext.MDC_KEY));
        Long pending=redis.execute(new DefaultRedisScript<>("return redis.call('XPENDING',KEYS[1],ARGV[1])[1]",Long.class),List.of(stream),group);
        assertEquals(2L,pending);
    }
    @Test void handlerFailureRestoresCallerAndLeavesItsMessagePending() {
        String stream="failure-"+UUID.randomUUID(),group="fixture";
        redis.opsForStream().add(StreamRecords.newRecord().ofMap(Map.of("correlationId","failed-record")).withStreamKey(stream));
        var reader=new RedisEventStreams(redis,stream,group,"fixture",1000,60000,"");
        MDC.put(CorrelationIdContext.MDC_KEY,"caller");
        assertThrows(IllegalStateException.class,()->reader.poll(record->{assertEquals("failed-record",MDC.get(CorrelationIdContext.MDC_KEY));throw new IllegalStateException("fixture");}));
        assertEquals("caller",MDC.get(CorrelationIdContext.MDC_KEY));
        Long pending=redis.execute(new DefaultRedisScript<>("return redis.call('XPENDING',KEYS[1],ARGV[1])[1]",Long.class),List.of(stream),group);
        assertEquals(1L,pending);
    }
}
