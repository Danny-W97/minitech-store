package org.demo.billingservice.messaging;

import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.*;
public interface InvoiceOutboxRepository extends JpaRepository<InvoiceOutboxEvent,Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from InvoiceOutboxEvent e where e.id=:id") Optional<InvoiceOutboxEvent> lockId(Long id);
    @Query("select e.id from InvoiceOutboxEvent e where e.status in :statuses and e.nextAttemptAt<=:now and (e.processingUntil is null or e.processingUntil<=:now) order by e.createdAt,e.id")
    List<Long> due(List<InvoiceOutboxStatus> statuses,Instant now,org.springframework.data.domain.Pageable page);
}
