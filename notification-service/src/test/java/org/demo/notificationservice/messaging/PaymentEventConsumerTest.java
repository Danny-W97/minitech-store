package org.demo.notificationservice.messaging;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.connection.stream.*;
import java.util.Map;
import static org.mockito.Mockito.*;
class PaymentEventConsumerTest {
    @org.junit.jupiter.api.AfterEach void clearCorrelationFixture() { org.slf4j.MDC.clear(); }

    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final StreamOperations<String,Object,Object> operations=mock(StreamOperations.class);
    private final NotificationEventInbox inbox=mock(NotificationEventInbox.class);
    private final RejectedEventStore rejected=mock(RejectedEventStore.class);
    private PaymentEventConsumer consumer;
    @BeforeEach void setup() {
        org.slf4j.MDC.clear();
        when(redis.opsForStream()).thenReturn(operations);
        consumer=new PaymentEventConsumer(redis,inbox,rejected,"payment-events","notification-service","test",60000,604800000,"notification-service,billing-service");
    }
    private MapRecord<String,String,String> record(Map<String,String> fields) { return StreamRecords.newRecord().ofMap(fields).withStreamKey("payment-events").withId(RecordId.of("10-0")); }
    @Test void durableEnqueueCommitsBeforeAck() {
        var record=record(Map.of("eventKey","PAYMENT_SUCCEEDED:11:owner@example.test","eventType","PAYMENT_SUCCEEDED","orderId","11","amount","10.00","recipient","owner@example.test"));
        consumer.processRecord(record);
        var ordered=inOrder(inbox,operations);ordered.verify(inbox).accept(anyString(),anyMap(),any());ordered.verify(operations).acknowledge("payment-events","notification-service",record.getId());
    }
    @Test void databaseFailureLeavesTheRecordPending() {
        var record=record(Map.of("eventType","PAYMENT_PENDING","orderId","11","amount","10.00","recipient","owner@example.test"));
        doThrow(new IllegalStateException("Database unavailable")).when(inbox).accept(anyString(),anyMap(),any());
        consumer.processRecord(record);verify(operations,never()).acknowledge(anyString(),anyString(),any(RecordId.class));
    }
    @Test void malformedEventIsCommittedToDlqBeforeAck() {
        var record=record(Map.of("eventType","broken"));consumer.processRecord(record);
        var ordered=inOrder(rejected,operations);ordered.verify(rejected).reject(eq("payment-events"),eq("10-0"),anyMap(),anyString());ordered.verify(operations).acknowledge("payment-events","notification-service",record.getId());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void nestedRecordScopeRestoresTheCallerAfterCommitOrFailure(boolean failure) {
        var record=record(Map.of("eventType","PAYMENT_PENDING","orderId","11","amount","10.00","recipient","fixture@example.test","correlationId","redis-record"));
        doAnswer(call->{
            org.junit.jupiter.api.Assertions.assertEquals("redis-record",org.slf4j.MDC.get(org.demo.platformobservability.CorrelationIdContext.MDC_KEY));
            if(failure)throw new IllegalStateException("fixture SQL unavailable");return null;
        }).when(inbox).accept(anyString(),anyMap(),any());
        try(var parent=org.demo.platformobservability.CorrelationIdContext.open("caller")) {
            consumer.processRecord(record);
            org.junit.jupiter.api.Assertions.assertEquals("caller",org.slf4j.MDC.get(org.demo.platformobservability.CorrelationIdContext.MDC_KEY));
        }
        org.junit.jupiter.api.Assertions.assertNull(org.slf4j.MDC.get(org.demo.platformobservability.CorrelationIdContext.MDC_KEY));
        if(failure)verify(operations,never()).acknowledge(anyString(),anyString(),any(RecordId.class));
        else verify(operations).acknowledge("payment-events","notification-service",record.getId());
    }
}
