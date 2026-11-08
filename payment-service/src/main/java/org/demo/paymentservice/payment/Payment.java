package org.demo.paymentservice.payment;

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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_payments_order_id", columnNames = "order_id")
)
public class Payment {
    @Column(name="provider_declined",nullable=false) private boolean providerDeclined;
    public boolean isProviderDeclined() { return providerDeclined; }
    public void markDeclined(String reason) { markFailed(reason);providerDeclined=true; }
    @Column(name="collection_reference",unique=true,length=80) private String collectionReference;
    @Column(name="collected_at") private Instant collectedAt;
    public String getCollectionReference() { return collectionReference; }
    public Instant getCollectedAt() { return collectedAt; }
    public void collect(String reference) { collectionReference=reference;collectedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);markSucceeded("CASH-"+reference); }
    @Column(name="request_fingerprint",length=64) private String requestFingerprint;
    @Column(name="result_recorded_at") private Instant resultRecordedAt;
    public String getRequestFingerprint() { return requestFingerprint; }
    public void bindRequest(String fingerprint) { requestFingerprint=fingerprint; }
    public boolean resultRecorded() { return resultRecordedAt!=null || status==PaymentStatus.SUCCEEDED; }
    public void recordResult() { resultRecordedAt=Instant.now(); }
    public void recordAuthorization(String reference) { providerReference=reference; }

    public void requireSameCommand(String fingerprint,String method) {
        if (!paymentMethod.equals(method) || (requestFingerprint!=null && !requestFingerprint.equals(fingerprint))) {
            throw new PaymentIdempotencyConflictException(orderId);
        }
    }
    @Column(name="processing_token",length=36) private String processingToken;
    @Column(name="processing_until") private Instant processingUntil;
    public String getProcessingToken() { return processingToken; }
    public boolean processing() { return processingUntil!=null && processingUntil.isAfter(Instant.now()); }
    public void claim(String token,Instant until) { processingToken=token; processingUntil=until; }
    public void release() { processingToken=null; processingUntil=null; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name="checkout_session_id",length=100,unique=true)
    private String checkoutSessionId;

    @Column(name="checkout_url",length=2000)
    private String checkoutUrl;
    @Column(name="stripe_intent_id",length=100,unique=true) private String stripeIntentId;
    @Column(name="stripe_paid_at") private Instant stripePaidAt;
    @Column(name="stripe_next_attempt_at") private Instant stripeNextAttemptAt;
    @Column(name="stripe_error",length=80) private String stripeError;
    @Column(name="stripe_compensation_required",nullable=false) private boolean stripeCompensationRequired;
    @Column(name="stripe_external_refund_id",length=100) private String stripeExternalRefundId;
    @Column(name="stripe_external_hold",nullable=false) private boolean stripeExternalHold;
    public String getStripeExternalRefundId() { return stripeExternalRefundId; }
    public boolean stripeExternalHold() { return stripeExternalHold; }
    public void externalRefund(String id,boolean hold) { stripeExternalRefundId=id;stripeExternalHold|=hold;stripeRetry("EXTERNAL_REFUND_PENDING"); }
    public String getStripeIntentId() { return stripeIntentId; }
    public boolean stripePaid() { return stripePaidAt!=null; }
    public boolean stripeCompensationRequired() { return stripeCompensationRequired; }
    public String getStripeError() { return stripeError; }
    public void recordStripePaid(String intent) {
        if(intent==null || !intent.matches("pi_[A-Za-z0-9_]{4,96}") || stripeIntentId!=null && !stripeIntentId.equals(intent))
            throw new StripeReviewRequiredException("PAYMENT_INTENT_CONFLICT");
        if(status==PaymentStatus.FAILED) { stripeCompensationRequired=true;status=PaymentStatus.PENDING;resultRecordedAt=null;providerDeclined=false; }
        stripeIntentId=intent;providerReference=intent;
        if(stripePaidAt==null) stripePaidAt=Instant.now();
        checkoutUrl=null;stripeRetry(null);
    }
    public void stripeRetry(String code) { stripeError=code;stripeNextAttemptAt=Instant.now().plusSeconds(30); }
    public void stripeResolved() { stripeError=null;stripeNextAttemptAt=null; }
    public void stripeReview(String code) { stripeError=code;stripeNextAttemptAt=null; }
    public void markRefunded() { status=PaymentStatus.REFUNDED;checkoutUrl=null;recordResult();stripeResolved(); }

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(Long orderId, BigDecimal amount, String paymentMethod) {
        this(orderId, amount, paymentMethod, "order-" + orderId);
    }

    public Payment(Long orderId, BigDecimal amount, String paymentMethod, String idempotencyKey) {
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = "PLN";
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.PENDING;
    }

    public void markSucceeded(String providerReference) {
        this.status = PaymentStatus.SUCCEEDED;
        this.providerReference = providerReference;
        this.checkoutUrl = null;
        this.failureReason = null;
    }

    public void markFailed(String failureReason) {
        this.status = PaymentStatus.FAILED;
        this.providerReference = null;
        this.checkoutUrl = null;
        this.failureReason = failureReason;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getCheckoutSessionId() { return checkoutSessionId; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public void recordCheckout(String sessionId,String url) {
        if(status!=PaymentStatus.PENDING || sessionId==null || !sessionId.startsWith("cs_")
                || url==null || !url.startsWith("https://checkout.stripe.com/"))
            throw new IllegalArgumentException("Invalid pending Stripe Checkout session");
        if(checkoutSessionId!=null && (!checkoutSessionId.equals(sessionId) || !url.equals(checkoutUrl)))
            throw new IllegalStateException("Payment already has a different Stripe Checkout session");
        checkoutSessionId=sessionId;checkoutUrl=url;providerReference=sessionId;
        stripeRetry(null);
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
}
