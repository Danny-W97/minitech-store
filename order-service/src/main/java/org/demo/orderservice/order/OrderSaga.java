package org.demo.orderservice.order;

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
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "order_sagas")
public class OrderSaga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderSagaState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected OrderSaga() {
    }

    public OrderSaga(Long orderId) {
        this.orderId = orderId;
        this.state = OrderSagaState.INVENTORY_RESERVATION_PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = Instant.now();
    }

    public void recordAttempt(Instant nextAttemptAt, String error) {
        this.attemptCount++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
    }

    public void waitingForPayment() {
        this.state = OrderSagaState.WAITING_FOR_PAYMENT;
        this.attemptCount = 0;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void compensationPending(Instant nextAttemptAt, String error) {
        this.state = OrderSagaState.COMPENSATION_PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
    }

    public void completed() {
        this.state = OrderSagaState.COMPLETED;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void failed(String error) {
        this.state = OrderSagaState.FAILED;
        this.nextAttemptAt = null;
        this.lastError = error;
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

    public OrderSagaState getState() {
        return state;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
