package org.demo.customerservice.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "auth_method", nullable = false, length = 40)
    private String authenticationMethod;

    protected RefreshToken() {
    }

    public RefreshToken(Long customerId, String tokenHash, Instant expiresAt) {
        this(customerId, tokenHash, expiresAt, "pwd");
    }

    public RefreshToken(Long customerId, String tokenHash, Instant expiresAt, String authenticationMethod) {
        this.customerId = customerId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.authenticationMethod = authenticationMethod == null || authenticationMethod.isBlank()
                ? "pwd" : authenticationMethod;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getAuthenticationMethod() { return authenticationMethod; }
}
