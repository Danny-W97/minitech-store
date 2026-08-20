package org.demo.notificationservice.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationDeadLetterService {

    private final NotificationDeadLetterRepository repository;

    public NotificationDeadLetterService(NotificationDeadLetterRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Notification notification) {
        if (notification.getId() == null) {
            return;
        }

        NotificationDeadLetterEntry entry = repository.findByNotificationId(notification.getId())
                .orElseGet(() -> new NotificationDeadLetterEntry(notification));
        if (entry.getId() != null) {
            entry.refreshFrom(notification);
        }
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<NotificationDeadLetterEntry> getOpenEntries() {
        return repository.findAllByStatusOrderByCreatedAtAsc(DeadLetterStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public NotificationDeadLetterEntry get(Long deadLetterId) {
        return repository.findById(deadLetterId)
                .orElseThrow(() -> new NotificationDeadLetterNotFoundException(deadLetterId));
    }

    @Transactional
    public void resolve(NotificationDeadLetterEntry entry) {
        entry.markResolved();
        repository.save(entry);
    }

    @Transactional
    public void resolveByNotificationId(Long notificationId) {
        repository.findByNotificationId(notificationId).ifPresent(this::resolve);
    }

    public long countOpen() {
        return repository.countByStatus(DeadLetterStatus.OPEN);
    }
}
