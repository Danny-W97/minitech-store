package org.demo.paymentservice.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEvent, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from PaymentOutboxEvent e where e.id=:id")
    Optional<PaymentOutboxEvent> lockId(Long id);
    @org.springframework.data.jpa.repository.Query("select e.id from PaymentOutboxEvent e where e.status in :statuses and e.nextAttemptAt<=:now and (e.processingUntil is null or e.processingUntil<=:now) order by e.createdAt,e.id")
    List<Long> due(List<OutboxStatus> statuses,Instant now,org.springframework.data.domain.Pageable page);

    Optional<PaymentOutboxEvent> findByEventKey(String eventKey);

    List<PaymentOutboxEvent> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<OutboxStatus> statuses,
            Instant now
    );
}
