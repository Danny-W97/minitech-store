package org.demo.notificationservice.messaging;

import org.demo.notificationservice.notification.*;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a32inbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","notification.retry.enabled=false"})
class EventInboxTest {
    @org.junit.jupiter.api.AfterEach void clearCorrelationFixture() { org.slf4j.MDC.clear(); }

    @Autowired NotificationEventInbox inbox;
    @Autowired EventReceiptRepository receipts;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationStateStore states;
    @Autowired NotificationService service;
    @Autowired NotificationDeadLetterRepository dead;
    @MockitoBean NotificationDeliveryService delivery;
    private final CreateNotificationRequest request=new CreateNotificationRequest(NotificationEventType.PAYMENT_SUCCEEDED,81L,"owner@example.test",NotificationChannel.EMAIL,"Title","Content",false);
    @BeforeEach void clear() { org.slf4j.MDC.clear();receipts.deleteAll();dead.deleteAll();notifications.deleteAll(); }
    @Test void duplicateRecordsProduceOneDurableJobAndNoSendUntilTheWorkerRuns() throws Exception {
        var fields=Map.of("eventType","PAYMENT_SUCCEEDED","orderId","81","amount","10.00","recipient","owner@example.test");
        try(var workers=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var results=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) results.add(workers.submit(() -> {
                try { gate.await(); } catch(InterruptedException error) { throw new RuntimeException(error); }
                try { inbox.accept("event-one",fields,request); }
                catch(org.springframework.dao.DataIntegrityViolationException race) { inbox.accept("event-one",fields,request); }
            }));
            gate.countDown();for(var result:results) result.get(5,TimeUnit.SECONDS);
        }
        inbox.accept("event-two",fields,request);
        assertThat(receipts.count()).isEqualTo(2);assertThat(notifications.count()).isEqualTo(1);verifyNoInteractions(delivery);
        assertThatThrownBy(() -> inbox.accept("event-one",Map.of("amount","11.00"),request)).isInstanceOf(IllegalArgumentException.class);
        when(delivery.deliver(any(),eq(false))).thenReturn(NotificationDeliveryResult.sent("ONCE"));
        long id=notifications.findAll().getFirst().getId();service.retry(id);service.retry(id);
        verify(delivery,times(1)).deliver(any(),eq(false));
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.SENT);
    }
    @Test void uncertainDeliveryGoesToDlqWithoutAutomaticResend() {
        when(delivery.deliver(any(),eq(false))).thenReturn(NotificationDeliveryResult.uncertain("Acceptance was not confirmed"));
        var response=service.create(request);service.retry(response.id());
        assertThat(dead.count()).isEqualTo(1);assertThat(notifications.findById(response.id()).orElseThrow().getNextAttemptAt()).isNull();
        verify(delivery,times(1)).deliver(any(),eq(false));
    }
}
