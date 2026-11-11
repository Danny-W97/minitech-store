package org.demo.notificationservice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notification_dead_letters")
public class NotificationDeadLetterEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "notification_id", nullable = false, unique = true)
    private Long notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private NotificationEventType eventType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(length = 255)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(length = 120)
    private String subject;

    @Column(length = 6000)
    private String message;

    @Column(name = "content_compacted_at")
    private Instant contentCompactedAt;

    public boolean isContentCompacted() { return contentCompactedAt != null; }

    public void requireReplayContent() {
        if (isContentCompacted()) throw new IllegalStateException("Resolved dead-letter content is compacted");
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeadLetterStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected NotificationDeadLetterEntry() {
    }

    public NotificationDeadLetterEntry(Notification notification) {
        notification.requireDeliveryContent();
        this.notificationId = notification.getId();
        this.eventType = notification.getEventType();
        this.sourceId = notification.getSourceId();
        this.recipient = notification.getRecipient();
        this.channel = notification.getChannel();
        this.subject = notification.getSubject();
        this.message = notification.getMessage();
        this.status = DeadLetterStatus.OPEN;
        this.failureReason = notification.getFailureReason();
        this.attemptCount = notification.getAttemptCount();
    }

    public void refreshFrom(Notification notification) {
        requireReplayContent();
        notification.requireDeliveryContent();
        this.failureReason = notification.getFailureReason();
        this.attemptCount = notification.getAttemptCount();
        this.status = DeadLetterStatus.OPEN;
        this.resolvedAt = null;
    }

    public void markResolved() {
        if (status == DeadLetterStatus.RESOLVED) return;
        this.status = DeadLetterStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public NotificationEventType getEventType() {
        return eventType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getRecipient() {
        return recipient;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getSubject() {
        return subject;
    }

    public String getMessage() {
        return message;
    }

    public DeadLetterStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
