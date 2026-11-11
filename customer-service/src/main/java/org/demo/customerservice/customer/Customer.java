package org.demo.customerservice.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

@Entity
@Table(name = "customers")
@DynamicUpdate
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "verification_token_hash", length = 64)
    private String verificationTokenHash;

    @Column(name = "verification_expires_at")
    private Instant verificationExpiresAt;

    @Column(name = "verification_resend_count", nullable = false)
    private int verificationResendCount;

    @Column(name = "verification_resend_window_started_at")
    private Instant verificationResendWindowStartedAt;

    @Column(name = "verification_last_sent_at")
    private Instant verificationLastSentAt;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 200)
    private String street;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(nullable = false, length = 120)
    private String city;

    @Column(nullable = false, length = 120)
    private String country;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {
    }

    public Customer(String email, String passwordHash, String firstName, String lastName,
                    String street, String postalCode, String city, String country) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        updateAddress(street, postalCode, city, country);
        this.enabled = false;
    }

    public void startVerification(String tokenHash, Instant expiresAt) {
        Instant sentAt = Instant.now();
        this.enabled = false;
        this.verificationTokenHash = tokenHash;
        this.verificationExpiresAt = expiresAt;
        this.verificationResendCount = 0;
        this.verificationResendWindowStartedAt = sentAt;
        this.verificationLastSentAt = sentAt;
    }

    public boolean canResendVerification(
            Instant now,
            long minimumIntervalSeconds,
            int maximumAttempts,
            long windowSeconds
    ) {
        // An expired hash can be purged; the expiry remains the pending-registration marker.
        if (enabled || verificationExpiresAt == null) {
            return false;
        }
        if (verificationLastSentAt != null
                && verificationLastSentAt.plusSeconds(minimumIntervalSeconds).isAfter(now)) {
            return false;
        }
        int attemptsInWindow = verificationResendCount;
        if (verificationResendWindowStartedAt == null
                || !verificationResendWindowStartedAt.plusSeconds(windowSeconds).isAfter(now)) {
            attemptsInWindow = 0;
        }
        return attemptsInWindow < maximumAttempts;
    }

    public void resendVerification(String tokenHash, Instant expiresAt, Instant sentAt, long windowSeconds) {
        if (verificationResendWindowStartedAt == null
                || !verificationResendWindowStartedAt.plusSeconds(windowSeconds).isAfter(sentAt)) {
            verificationResendWindowStartedAt = sentAt;
            verificationResendCount = 0;
        }
        verificationResendCount++;
        verificationTokenHash = tokenHash;
        verificationExpiresAt = expiresAt;
        verificationLastSentAt = sentAt;
    }

    public void confirmVerification() {
        this.enabled = true;
        this.verificationTokenHash = null;
        this.verificationExpiresAt = null;
    }

    public void updateAddress(String street, String postalCode, String city, String country) {
        this.street = street;
        this.postalCode = postalCode;
        this.city = city;
        this.country = country;
    }

    public void updateProfile(
            String firstName,
            String lastName,
            String street,
            String postalCode,
            String city,
            String country,
            String phoneNumber
    ) {
        this.firstName = firstName;
        this.lastName = lastName;
        updateAddress(street, postalCode, city, country);
        this.phoneNumber = phoneNumber == null || phoneNumber.isBlank() ? null : phoneNumber;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
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
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public String getVerificationTokenHash() { return verificationTokenHash; }
    public Instant getVerificationExpiresAt() { return verificationExpiresAt; }
    public int getVerificationResendCount() { return verificationResendCount; }
    public Instant getVerificationResendWindowStartedAt() { return verificationResendWindowStartedAt; }
    public Instant getVerificationLastSentAt() { return verificationLastSentAt; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getStreet() { return street; }
    public String getPostalCode() { return postalCode; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getPhoneNumber() { return phoneNumber; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
