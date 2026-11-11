package org.demo.notificationservice.notification;

import org.demo.platformobservability.CorrelationIdContext;
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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notifications_event_source_recipient",
                columnNames = {"event_type", "source_id", "recipient"}
        )
)
public class Notification {
    @Column(name="subject_customer_id") private Long subjectCustomerId;
    public Long getSubjectCustomerId(){return subjectCustomerId;}
    public void accountSubject(Long id){if(subjectCustomerId!=null&&!subjectCustomerId.equals(id))throw new IllegalArgumentException("Notification subject is immutable");subjectCustomerId=id;}
    @Column(name="privacy_redacted",nullable=false) private boolean privacyRedacted;
    @Column(name="recipient_hash",length=64) private String recipientHash;
    @Column(name="processing_token",length=36) private String processingToken;
    @Column(name="processing_until") private Instant processingUntil;
    public void queueForDelivery() { requireDeliveryContent();processingToken="queued";processingUntil=Instant.EPOCH; }
    public String getProcessingToken() { return processingToken; }
    public boolean processing() { return processingUntil!=null && processingUntil.isAfter(Instant.now()); }
    public void claim(String token,Instant until) { requireDeliveryContent();processingToken=token; processingUntil=until; }
    public void release() { processingToken=null; processingUntil=null; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    public void requireDeliveryContent() {
        if (isContentCompacted()) throw new IllegalStateException("Sent notification content is compacted");
    }

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    protected Notification() {
    }

    public Notification(
            NotificationEventType eventType,
            Long sourceId,
            String recipient,
        NotificationChannel channel,
        String subject,
            String message
    ) {
        this(eventType, sourceId, recipient, channel, subject, message, CorrelationIdContext.currentOrNew());
    }

    public Notification(
            NotificationEventType eventType,
            Long sourceId,
            String recipient,
            NotificationChannel channel,
            String subject,
            String message,
            String correlationId
    ) {
        this(eventType, sourceId, recipient, channel, subject, message, correlationId, null);
    }

    public Notification(
            NotificationEventType eventType,
            Long sourceId,
            String recipient,
            NotificationChannel channel,
            String subject,
            String message,
            String correlationId,
            Long invoiceId
    ) {
        this.eventType = eventType;
        this.sourceId = sourceId;
        this.recipient = recipient;
        this.channel = channel;
        this.subject = subject;
        this.message = message;
        this.correlationId = CorrelationIdContext.resolve(correlationId);
        this.invoiceId = invoiceId;
        this.status = NotificationStatus.PENDING;
        this.attemptCount = 0;
    }

    public void prepareForRetry(
            String recipient,
            NotificationChannel channel,
            String subject,
            String message
    ) {
        prepareForRetry(recipient, channel, subject, message, correlationId);
    }

    public void prepareForRetry(
            String recipient,
            NotificationChannel channel,
            String subject,
            String message,
            String correlationId
    ) {
        prepareForRetry(recipient, channel, subject, message, correlationId, invoiceId);
    }

    public void prepareForRetry(
            String recipient,
            NotificationChannel channel,
            String subject,
            String message,
            String correlationId,
            Long invoiceId
    ) {
        requireDeliveryContent();
        this.recipient = recipient;
        this.channel = channel;
        this.subject = subject;
        this.message = message;
        this.correlationId = CorrelationIdContext.resolve(correlationId);
        this.invoiceId = invoiceId;
        this.status = NotificationStatus.PENDING;
        this.providerReference = null;
        this.failureReason = null;
        this.sentAt = null;
        this.attemptCount = 0;
        this.nextAttemptAt = null;
    }

    public void startDeliveryAttempt() {
        requireDeliveryContent();
        this.status = NotificationStatus.PENDING;
        this.attemptCount++;
        this.nextAttemptAt = null;
    }

    public void markSent(String providerReference) {
        requireDeliveryContent();
        this.status = NotificationStatus.SENT;
        this.providerReference = providerReference;
        this.failureReason = null;
        this.sentAt = Instant.now();
        this.nextAttemptAt = null;
    }

    public void markFailed(String failureReason, Instant nextAttemptAt) {
        requireDeliveryContent();
        this.status = NotificationStatus.FAILED;
        this.providerReference = null;
        this.failureReason = failureReason;
        this.sentAt = null;
        this.nextAttemptAt = nextAttemptAt;
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

    public String getCorrelationId() {
        return correlationId;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
