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

    public void prepareForRetry(BigDecimal amount, String paymentMethod) {
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.PENDING;
        this.providerReference = null;
        this.failureReason = null;
    }

    public void markSucceeded(String providerReference) {
        this.status = PaymentStatus.SUCCEEDED;
        this.providerReference = providerReference;
        this.failureReason = null;
    }

    public void markFailed(String failureReason) {
        this.status = PaymentStatus.FAILED;
        this.providerReference = null;
        this.failureReason = failureReason;
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
