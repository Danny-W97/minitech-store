package org.demo.customerservice.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "customer_totp_methods")
public class TotpMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String secretCiphertext;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_used_time_step")
    private Long lastUsedTimeStep;

    @Column(name = "enrollment_expires_at")
    private Instant enrollmentExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TotpMethod() {
    }

    public TotpMethod(Long customerId, String secretCiphertext) {
        this.customerId = customerId;
        this.secretCiphertext = secretCiphertext;
        this.enabled = false;
    }

    public void enable() {
        enabled = true;
        enrollmentExpiresAt = null;
    }

    public void disable() {
        enabled = false;
        lastUsedTimeStep = null;
    }

    public void restartEnrollment(String secretCiphertext) {
        this.secretCiphertext = secretCiphertext;
        this.enabled = false;
        this.lastUsedTimeStep = null;
    }

    public void enrollmentExpiresAt(Instant expiresAt) { this.enrollmentExpiresAt = expiresAt; }
    public Instant getEnrollmentExpiresAt() { return enrollmentExpiresAt; }

    public boolean canUseTimeStep(long timeStep) {
        return lastUsedTimeStep == null || timeStep > lastUsedTimeStep;
    }

    public void markUsed(long timeStep) {
        lastUsedTimeStep = timeStep;
    }

    public void reencryptSecret(String ciphertext) {
        this.secretCiphertext = ciphertext;
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

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public boolean isEnabled() { return enabled; }
    public Long getLastUsedTimeStep() { return lastUsedTimeStep; }
}
