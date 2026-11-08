package org.demo.paymentservice.outbox;

import org.demo.platformobservability.CorrelationIdContext;
import org.demo.paymentservice.payment.PaymentStatus;

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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_outbox_events")
public class PaymentOutboxEvent {
    @Column(name="processing_token",length=36) private String processingToken;
    @Column(name="processing_until") private Instant processingUntil;
    public String getProcessingToken() { return processingToken; }
    public boolean processing() { return processingUntil!=null && processingUntil.isAfter(Instant.now()); }
    public void claim(String token,Instant until) { processingToken=token;processingUntil=until; }
    public void release() { processingToken=null;processingUntil=null; }
    @Column(name="stored_event_id",length=128) private String storedEventId;
    @Column(name="identity_version",nullable=false) private int identityVersion=1;
    public int getIdentityVersion(){return identityVersion;}
    public String getEventId() { return storedEventId!=null?storedEventId:org.demo.platformevents.EventIdentity.payment(eventKey); }
    public String getLegacyEventId(){return identityVersion==2?"payment-"+eventKey.substring(3):getEventId();}
    public void useNewIdentity(){
        if(id!=null||identityVersion!=1)throw new IllegalStateException("Existing outbox identity is immutable");
        storedEventId="payment-v2-"+java.util.UUID.randomUUID();eventKey="v2:"+org.demo.platformevents.EventIdentity.hash(eventKey);identityVersion=2;
    }


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 400)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String recipient;

    @Column(name = "delivery_address", length = 1000)
    private String deliveryAddress;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "payload_compacted_at")
    private Instant payloadCompactedAt;

    public boolean isPayloadCompacted() { return payloadCompactedAt != null; }

    public void requirePublishablePayload() {
        if (isPayloadCompacted()) throw new IllegalStateException("Payment outbox payload is compacted");
    }

    protected PaymentOutboxEvent() {
    }

    public PaymentOutboxEvent(String eventType, Long orderId, BigDecimal amount, String recipient) {
        this(eventType, orderId, amount, recipient, null, CorrelationIdContext.currentOrNew());
    }

    public PaymentOutboxEvent(String eventType, Long orderId, BigDecimal amount, String recipient,
                              String deliveryAddress) {
        this(eventType, orderId, amount, recipient, deliveryAddress, CorrelationIdContext.currentOrNew());
    }

    public PaymentOutboxEvent(String eventType, Long orderId, BigDecimal amount, String recipient,
                              String deliveryAddress, String correlationId) {
        this(eventType, orderId, amount, recipient, deliveryAddress, correlationId, null);
    }

    public PaymentOutboxEvent(String eventType, Long orderId, BigDecimal amount, String recipient,
                              String deliveryAddress, String correlationId, Long invoiceId) {
        this.eventKey = "%s:%d:%s".formatted(eventType, orderId, recipient);
        this.eventType = eventType;
        this.orderId = orderId;
        this.amount = amount;
        this.recipient = recipient;
        this.deliveryAddress = deliveryAddress;
        this.correlationId = CorrelationIdContext.resolve(correlationId);
        this.invoiceId = invoiceId;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.lastError = null;
        this.publishedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = OutboxStatus.FAILED;
        this.attempts++;
        this.lastError = error;
        long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
        this.nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
    }

    public PaymentStatus paymentStatus() {
        return switch (eventType) {
            case "PAYMENT_SUCCEEDED" -> PaymentStatus.SUCCEEDED;
            case "PAYMENT_PENDING" -> PaymentStatus.PENDING;
            default -> PaymentStatus.FAILED;
        };
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

    public String getEventKey() {
        return eventKey;
    }

    public String getEventType() {
        return eventType;
    }

    public Long getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
