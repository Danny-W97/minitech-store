package org.demo.paymentservice.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEvent, Long> {

    Optional<PaymentOutboxEvent> findByEventKey(String eventKey);

    List<PaymentOutboxEvent> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<OutboxStatus> statuses,
            Instant now
    );
}
