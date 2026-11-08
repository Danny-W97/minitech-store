package org.demo.notificationservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationDeadLetterRepository
        extends JpaRepository<NotificationDeadLetterEntry, Long> {

    Optional<NotificationDeadLetterEntry> findByNotificationId(Long notificationId);

    List<NotificationDeadLetterEntry> findAllByStatusOrderByCreatedAtAsc(DeadLetterStatus status);

    @org.springframework.data.jpa.repository.Query("select n from NotificationDeadLetterEntry n where n.id > :cursor "
            + "and n.status = org.demo.notificationservice.notification.DeadLetterStatus.OPEN order by n.id asc")
    List<NotificationDeadLetterEntry> page(@org.springframework.data.repository.query.Param("cursor") long cursor,
            org.springframework.data.domain.Pageable pageable);

    long countByStatus(DeadLetterStatus status);
}
