package org.demo.notificationservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select n from Notification n where n.id=:id")
    Optional<Notification> lockId(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select n from Notification n where n.eventType=:event and n.sourceId=:source and n.recipient=:recipient")
    Optional<Notification> lockKey(@org.springframework.data.repository.query.Param("event") NotificationEventType event,
            @org.springframework.data.repository.query.Param("source") Long source,@org.springframework.data.repository.query.Param("recipient") String recipient);
    @org.springframework.data.jpa.repository.Query("select n.id from Notification n where (n.status=org.demo.notificationservice.notification.NotificationStatus.FAILED and n.nextAttemptAt<=:now) or (n.status=org.demo.notificationservice.notification.NotificationStatus.PENDING and n.processingToken is not null and n.processingUntil<=:now) order by n.id")
    List<Long> due(@org.springframework.data.repository.query.Param("now") Instant now,org.springframework.data.domain.Pageable page);

    Optional<Notification> findByEventTypeAndSourceIdAndRecipient(
            NotificationEventType eventType,
            Long sourceId,
            String recipient
    );

    List<Notification> findAllByOrderByCreatedAtDesc();

    List<Notification> findAllBySourceIdOrderByCreatedAtDesc(Long sourceId);

    @org.springframework.data.jpa.repository.Query("select n from Notification n where n.id <= :ceiling "
            + "and (:sourceId is null or n.sourceId = :sourceId) and (:status is null or n.status = :status) order by n.id desc")
    List<Notification> page(@org.springframework.data.repository.query.Param("ceiling") long ceiling,
            @org.springframework.data.repository.query.Param("sourceId") Long sourceId,
            @org.springframework.data.repository.query.Param("status") NotificationStatus status, org.springframework.data.domain.Pageable pageable);

    List<Notification> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            NotificationStatus status,
            Instant currentTime
    );

    long countByStatusAndNextAttemptAtIsNotNull(NotificationStatus status);
}
