package org.demo.paymentservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Small idempotency receipt; raw webhook payloads (which can contain customer data) are not stored. */
@Entity
@Table(name="stripe_webhook_receipts")
public class StripeWebhookReceipt {
    @Id @Column(name="event_id",length=255) private String eventId;
    @Column(name="event_type",nullable=false,length=120) private String eventType;
    @Column(name="payment_id") private Long paymentId;
    @Column(name="outcome",nullable=false,length=20) private String outcome;
    @Column(name="received_at",nullable=false,updatable=false) private Instant receivedAt;
    protected StripeWebhookReceipt() {}
    public StripeWebhookReceipt(String eventId,String eventType,Long paymentId,String outcome) {
        this.eventId=eventId;this.eventType=eventType;this.paymentId=paymentId;this.outcome=outcome;this.receivedAt=Instant.now();
    }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Long getPaymentId() { return paymentId; }
    public String getOutcome() { return outcome; }
    public Instant getReceivedAt() { return receivedAt; }
}
