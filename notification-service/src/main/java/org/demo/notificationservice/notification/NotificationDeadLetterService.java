package org.demo.notificationservice.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationDeadLetterService {

    private final NotificationDeadLetterRepository repository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;

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
            fresh(entry);
            entry.refreshFrom(notification);
        }
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<NotificationDeadLetterEntry> getOpenEntries() {
        return repository.page(0, org.demo.platformsecurity.ListWindow.FIRST.fetch()).stream().limit(50).toList();
    }

    @Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<org.demo.notificationservice.web.NotificationDeadLetterResponse> list(
            org.demo.platformsecurity.ListWindow window) {
        return window.map(repository.page(window.cursor(), window.fetch()), NotificationDeadLetterEntry::getId,
                org.demo.notificationservice.web.NotificationDeadLetterResponse::from);
    }

    @Transactional(readOnly = true)
    public NotificationDeadLetterEntry get(Long deadLetterId) {
        return repository.findById(deadLetterId)
                .orElseThrow(() -> new NotificationDeadLetterNotFoundException(deadLetterId));
    }

    @Transactional
    public void resolve(NotificationDeadLetterEntry entry) {
        var current = lock(entry.getId());
        current.markResolved();
        repository.save(current);
    }

    @Transactional
    public NotificationDeadLetterEntry lock(Long id) {
        var entry = repository.findById(id).orElseThrow(() -> new NotificationDeadLetterNotFoundException(id));
        // Refresh with the lock: a locking query would reject a stale managed version before it can be refreshed.
        fresh(entry);
        return entry;
    }

    private void fresh(NotificationDeadLetterEntry entry) {
        entities.refresh(entry, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Transactional
    public void resolveByNotificationId(Long notificationId) {
        repository.findByNotificationId(notificationId).ifPresent(this::resolve);
    }

    public long countOpen() {
        return repository.countByStatus(DeadLetterStatus.OPEN);
    }
}
