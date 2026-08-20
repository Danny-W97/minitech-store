package org.demo.customerservice.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "customer_recovery_codes")
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecoveryCode() {
    }

    public RecoveryCode(Long customerId, String codeHash) {
        this.customerId = customerId;
        this.codeHash = codeHash;
    }

    public boolean isUsable() {
        return usedAt == null;
    }

    public void markUsed() {
        usedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt() { return usedAt; }
}
