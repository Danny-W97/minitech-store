package org.demo.notificationservice.notification;

import org.demo.notificationservice.web.CreateNotificationRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a31notification;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.hikari.maximum-pool-size=1","notification.retry.enabled=false"})
class NotificationTransactionBoundaryTest {
    @org.junit.jupiter.api.AfterEach void clearCorrelationFixture() { org.slf4j.MDC.clear(); }

    @Autowired NotificationService service;
    @Autowired NotificationStateStore states;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationDeadLetterRepository dead;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean NotificationDeliveryService delivery;
    private CreateNotificationRequest request(long source) { return new CreateNotificationRequest(NotificationEventType.PAYMENT_SUCCEEDED,source,"owner@example.test",NotificationChannel.EMAIL,"Title","Content",false); }
    @BeforeEach void clear() { org.slf4j.MDC.clear();dead.deleteAll(); notifications.deleteAll(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void deliveryRestoresCallerCorrelationAfterSuccessOrFailure(boolean failure) {
        when(delivery.deliver(any(),eq(false))).thenAnswer(call->{
            assertThat(org.slf4j.MDC.get(org.demo.platformobservability.CorrelationIdContext.MDC_KEY)).isEqualTo("request-caller");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(failure)throw new IllegalStateException("fixture uncertain delivery");
            return NotificationDeliveryResult.sent("FIXTURE");
        });
        try(var scope=org.demo.platformobservability.CorrelationIdContext.open("request-caller")) {
            service.create(request(failure?202:201));
            assertThat(org.slf4j.MDC.get(org.demo.platformobservability.CorrelationIdContext.MDC_KEY)).isEqualTo("request-caller");
        }
        assertThat(org.slf4j.MDC.get(org.demo.platformobservability.CorrelationIdContext.MDC_KEY)).isNull();
    }
    @Test void deliveryRunsOutsideTheTransactionAndConcurrentCreateDoesNotSendAgain() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(delivery.deliver(any(),eq(false))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            return NotificationDeliveryResult.sent("TEST-SENT");
        });
        try(var worker=Executors.newSingleThreadExecutor()) {
            var result=worker.submit(() -> service.create(request(81)));
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                assertThat(notifications.count()).isEqualTo(1);
                assertThat(service.create(request(81)).status()).isEqualTo(NotificationStatus.PENDING);
            } finally { release.countDown(); }
            assertThat(result.get(5,TimeUnit.SECONDS).status()).isEqualTo(NotificationStatus.SENT);
            assertThat(service.create(request(81)).status()).isEqualTo(NotificationStatus.SENT);
            verify(delivery,times(1)).deliver(any(),eq(false));
        }
    }
    @Test void abandonedSendIsQuarantinedAndStaleCompletionIsIgnored() {
        var first=states.create(request(82)); long id=first.notification().getId();
        jdbc.update("update notifications set processing_until=? where id=?",java.sql.Timestamp.from(java.time.Instant.EPOCH),id);
        assertThat(notifications.due(java.time.Instant.now(),org.springframework.data.domain.PageRequest.of(0,20))).contains(id);
        service.retry(id);
        states.complete(first,NotificationDeliveryResult.failed("STALE"),false);
        var recovered=notifications.findById(id).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(recovered.getAttemptCount()).isEqualTo(1);
        assertThat(dead.count()).isEqualTo(1);
        verifyNoInteractions(delivery);
        assertThat(recovered.getProcessingToken()).isNull();
    }
}
