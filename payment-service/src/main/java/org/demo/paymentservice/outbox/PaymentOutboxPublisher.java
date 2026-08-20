package org.demo.paymentservice.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class PaymentOutboxPublisher {

    private final PaymentOutboxRepository outboxRepository;
    private final PaymentEventPublisher eventPublisher;

    public PaymentOutboxPublisher(
            PaymentOutboxRepository outboxRepository,
            PaymentEventPublisher eventPublisher
    ) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:5000}")
    public void publishPendingEvents() {
        List<PaymentOutboxEvent> events = outboxRepository
                .findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
                        Instant.now()
        );

        for (PaymentOutboxEvent event : events) {
            boolean published = eventPublisher.publish(event);
            if (published) {
                event.markPublished();
            } else {
                event.markFailed("Event broker is unavailable");
            }
            outboxRepository.save(event);
        }
    }
}
