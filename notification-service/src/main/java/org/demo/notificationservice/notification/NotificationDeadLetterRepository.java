package org.demo.notificationservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationDeadLetterRepository
        extends JpaRepository<NotificationDeadLetterEntry, Long> {

    Optional<NotificationDeadLetterEntry> findByNotificationId(Long notificationId);

    List<NotificationDeadLetterEntry> findAllByStatusOrderByCreatedAtAsc(DeadLetterStatus status);

    long countByStatus(DeadLetterStatus status);
}
