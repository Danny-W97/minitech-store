package org.demo.paymentservice.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxPublisherTest {

    @Mock
    private PaymentOutboxRepository outboxRepository;

    @Mock
    private PaymentEventPublisher eventPublisher;

    @InjectMocks
    private PaymentOutboxPublisher publisher;

    @Test
    void publishesPendingEvent() {
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                "PAYMENT_SUCCEEDED", 11L, new BigDecimal("699.99"), "customer@example.com"
        );
        when(outboxRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                any(), any(Instant.class)
        )).thenReturn(List.of(event));
        when(eventPublisher.publish(event)).thenReturn(true);

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxRepository).save(event);
    }

    @Test
    void failedDeliveryIsScheduledForRetry() {
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                "PAYMENT_FAILED", 11L, new BigDecimal("699.99"), "customer@example.com"
        );
        when(outboxRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                any(), any(Instant.class)
        )).thenReturn(List.of(event));
        when(eventPublisher.publish(event)).thenReturn(false);

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        verify(outboxRepository).save(event);
    }
}
