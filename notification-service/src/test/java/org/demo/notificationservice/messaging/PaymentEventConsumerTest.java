package org.demo.notificationservice.messaging;

import org.demo.notificationservice.notification.NotificationService;
import org.demo.notificationservice.notification.NotificationChannel;
import org.demo.notificationservice.notification.NotificationEventType;
import org.demo.notificationservice.notification.NotificationStatus;
import org.demo.notificationservice.web.NotificationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private NotificationService notificationService;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        consumer = new PaymentEventConsumer(
                redisTemplate,
                notificationService,
                "payment-events",
                "notification-service",
                "notification-consumer"
        );
    }

    @Test
    void paymentEventIsConvertedToNotificationAndAcknowledged() {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .ofMap(Map.of(
                        "eventKey", "PAYMENT_SUCCEEDED:11:customer@example.com",
                        "eventType", "PAYMENT_SUCCEEDED",
                        "orderId", "11",
                        "amount", "699.99",
                        "recipient", "customer@example.com"
                ))
                .withStreamKey("payment-events");
        org.mockito.Mockito.when(notificationService.create(any()))
                .thenReturn(new NotificationResponse(
                        1L,
                        NotificationEventType.PAYMENT_SUCCEEDED,
                        11L,
                        "customer@example.com",
                        NotificationChannel.EMAIL,
                        "Payment confirmed",
                        "Payment for order 11 was confirmed. Amount: 699.99 PLN.",
                        NotificationStatus.SENT,
                        "SMTP-12345678",
                        null,
                        Instant.now(),
                        Instant.now(),
                        Instant.now()
                ));

        consumer.processRecord(record);

        verify(notificationService).create(any());
        verify(streamOperations).acknowledge(
                any(String.class), any(String.class), any(RecordId.class)
        );
    }
}
