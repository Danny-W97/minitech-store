package org.demo.notificationservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByEventTypeAndSourceIdAndRecipient(
            NotificationEventType eventType,
            Long sourceId,
            String recipient
    );

    List<Notification> findAllByOrderByCreatedAtDesc();

    List<Notification> findAllBySourceIdOrderByCreatedAtDesc(Long sourceId);

    List<Notification> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            NotificationStatus status,
            Instant currentTime
    );

    long countByStatusAndNextAttemptAtIsNotNull(NotificationStatus status);
}
