package org.demo.customerservice.customer;

import org.demo.customerservice.web.CustomerRequests;
import org.demo.customerservice.web.CustomerResponse;
import org.demo.customerservice.web.RegistrationResponse;
import org.demo.customerservice.notification.RegistrationNotificationClient;
import org.demo.customerservice.auth.PasswordResetToken;
import org.demo.customerservice.auth.PasswordResetTokenRepository;
import org.demo.customerservice.auth.RefreshToken;
import org.demo.customerservice.auth.RefreshTokenRepository;
import org.demo.customerservice.observability.CorrelationIdContext;
import org.demo.customerservice.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationNotificationClient notificationClient;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;
    private final String verificationLinkBaseUrl;
    private final long verificationTtlSeconds;
    private final String passwordResetLinkBaseUrl;
    private final long passwordResetTtlSeconds;
    private final long verificationResendMinimumIntervalSeconds;
    private final int verificationResendMaximumAttempts;
    private final long verificationResendWindowSeconds;

    public CustomerService(
            CustomerRepository repository,
            PasswordEncoder passwordEncoder,
            RegistrationNotificationClient notificationClient,
            PasswordResetTokenRepository passwordResetTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuditService auditService,
            @Value("${customer.verification.link-base-url:http://127.0.0.1:18070/?verify=}") String verificationLinkBaseUrl,
            @Value("${customer.verification.ttl-seconds:300}") long verificationTtlSeconds,
            @Value("${customer.verification.resend.min-interval-seconds:60}") long verificationResendMinimumIntervalSeconds,
            @Value("${customer.verification.resend.max-attempts:3}") int verificationResendMaximumAttempts,
            @Value("${customer.verification.resend.window-seconds:3600}") long verificationResendWindowSeconds,
            @Value("${customer.password-reset.link-base-url:http://127.0.0.1:18070/?reset=}") String passwordResetLinkBaseUrl,
            @Value("${customer.password-reset.ttl-seconds:900}") long passwordResetTtlSeconds
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.notificationClient = notificationClient;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditService = auditService;
        this.verificationLinkBaseUrl = verificationLinkBaseUrl;
        this.verificationTtlSeconds = verificationTtlSeconds;
        this.passwordResetLinkBaseUrl = passwordResetLinkBaseUrl;
        this.passwordResetTtlSeconds = passwordResetTtlSeconds;
        if (verificationTtlSeconds < 1
                || verificationResendMinimumIntervalSeconds < 1
                || verificationResendMaximumAttempts < 1
                || verificationResendWindowSeconds < verificationResendMinimumIntervalSeconds) {
            throw new IllegalArgumentException("Customer verification settings are invalid");
        }
        this.verificationResendMinimumIntervalSeconds = verificationResendMinimumIntervalSeconds;
        this.verificationResendMaximumAttempts = verificationResendMaximumAttempts;
        this.verificationResendWindowSeconds = verificationResendWindowSeconds;
    }

    @Transactional
    public RegistrationResponse register(CustomerRequests.Register request) {
        String email = normalize(request.email());
        Instant expiresAt = Instant.now().plus(verificationTtlSeconds, ChronoUnit.SECONDS);
        String rawToken = generateToken();
        String tokenHash = hashToken(rawToken);
        Customer customer = repository.findByEmail(email).orElse(null);

        if (customer != null && customer.isEnabled()) {
            throw new DuplicateEmailException(email);
        }
        if (customer != null) {
            throw new PendingRegistrationException(email);
        }

        customer = new Customer(
                email,
                passwordEncoder.encode(request.password()),
                request.firstName().trim(),
                request.lastName().trim(),
                request.street().trim(),
                request.postalCode().trim(),
                request.city().trim(),
                request.country().trim()
        );
        customer.startVerification(tokenHash, expiresAt);

        Customer saved = repository.saveAndFlush(customer);
        notificationClient.sendConfirmation(saved, verificationLinkBaseUrl + rawToken);
        auditService.record("CUSTOMER_REGISTERED", saved.getEmail(), "CUSTOMER", "PENDING", "verification_required");
        return new RegistrationResponse(
                saved.getEmail(),
                true,
                saved.getVerificationExpiresAt(),
                "Check your email and confirm the account within 5 minutes"
        );
    }

    @Transactional
    public void resendVerification(CustomerRequests.ResendVerification request) {
        String email = normalize(request.email());
        Customer customer = repository.findByEmailForUpdate(email).orElse(null);
        if (customer == null || customer.isEnabled()) {
            return;
        }

        Instant sentAt = Instant.now();
        if (!customer.canResendVerification(
                sentAt,
                verificationResendMinimumIntervalSeconds,
                verificationResendMaximumAttempts,
                verificationResendWindowSeconds
        )) {
            return;
        }

        String rawToken = generateToken();
        customer.resendVerification(
                hashToken(rawToken),
                sentAt.plus(verificationTtlSeconds, ChronoUnit.SECONDS),
                sentAt,
                verificationResendWindowSeconds
        );
        Customer saved = repository.saveAndFlush(customer);
        notificationClient.sendConfirmation(saved, verificationLinkBaseUrl + rawToken);
        auditService.record("VERIFICATION_RESENT", saved.getEmail(), "CUSTOMER", "ACCEPTED", "");
    }

    @Transactional
    public void requestPasswordReset(CustomerRequests.PasswordResetRequest request) {
        String email = normalize(request.email());
        Customer customer = repository.findByEmail(email).orElse(null);
        if (customer == null || !customer.isEnabled()) {
            auditService.record("PASSWORD_RESET_REQUESTED", email, "CUSTOMER", "ACCEPTED", "account_not_disclosed");
            return;
        }

        String rawToken = generateToken();
        PasswordResetToken token = new PasswordResetToken(
                customer.getId(),
                hashToken(rawToken),
                Instant.now().plus(passwordResetTtlSeconds, ChronoUnit.SECONDS)
        );
        passwordResetTokenRepository.save(token);
        notificationClient.sendPasswordReset(customer, passwordResetLinkBaseUrl + rawToken, token.getId());
        auditService.record("PASSWORD_RESET_REQUESTED", customer.getEmail(), "CUSTOMER", "SENT", "");
    }

    @Transactional
    public void confirmPasswordReset(CustomerRequests.PasswordResetConfirm request) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(hashToken(request.token()))
                .orElseThrow(InvalidPasswordResetTokenException::new);
        if (!token.isUsable(Instant.now())) {
            auditService.record("PASSWORD_RESET_CONFIRMED", "anonymous", "CUSTOMER", "REJECTED", "expired_or_used");
            throw new InvalidPasswordResetTokenException();
        }
        Customer customer = repository.findById(token.getCustomerId())
                .orElseThrow(InvalidPasswordResetTokenException::new);
        customer.changePassword(passwordEncoder.encode(request.password()));
        token.markUsed();
        refreshTokenRepository.findByCustomerIdAndRevokedAtIsNull(customer.getId())
                .forEach(refreshToken -> {
                    refreshToken.revoke();
                    refreshTokenRepository.save(refreshToken);
                });
        repository.save(customer);
        passwordResetTokenRepository.save(token);
        auditService.record("PASSWORD_RESET_CONFIRMED", customer.getEmail(), "CUSTOMER", "SUCCESS", "sessions_revoked=true");
    }

    @Transactional(readOnly = true)
    public Customer authenticate(CustomerRequests.Login request) {
        Customer customer = repository.findByEmail(normalize(request.email()))
                .orElseThrow(InvalidCredentialsException::new);
        if (!customer.isEnabled() || !passwordEncoder.matches(request.password(), customer.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return customer;
    }

    @Transactional
    public CustomerResponse verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidVerificationTokenException();
        }
        Customer customer = repository.findByVerificationTokenHash(hashToken(rawToken))
                .orElseThrow(InvalidVerificationTokenException::new);
        if (customer.isEnabled() || customer.getVerificationExpiresAt() == null
                || !customer.getVerificationExpiresAt().isAfter(Instant.now())) {
            auditService.record("CUSTOMER_VERIFICATION", "anonymous", "CUSTOMER", "REJECTED", "expired_or_used");
            throw new InvalidVerificationTokenException();
        }
        customer.confirmVerification();
        auditService.record("CUSTOMER_VERIFICATION", customer.getEmail(), "CUSTOMER", "SUCCESS", "");
        return CustomerResponse.from(repository.save(customer));
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long id) {
        return repository.findById(id)
                .map(CustomerResponse::from)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }

    @Transactional
    public CustomerResponse updateAddress(Long id, CustomerRequests.Address request) {
        Customer customer = repository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        customer.updateAddress(request.street().trim(), request.postalCode().trim(),
                request.city().trim(), request.country().trim());
        return CustomerResponse.from(repository.save(customer));
    }

    @Transactional
    public CustomerResponse updateProfile(Long id, CustomerRequests.Profile request) {
        Customer customer = repository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        customer.updateProfile(
                request.firstName().trim(),
                request.lastName().trim(),
                request.street().trim(),
                request.postalCode().trim(),
                request.city().trim(),
                request.country().trim(),
                request.phoneNumber() == null ? null : request.phoneNumber().trim()
        );
        Customer saved = repository.save(customer);
        auditService.record("CUSTOMER_PROFILE_UPDATED", saved.getEmail(), "CUSTOMER", "SUCCESS", "contact_and_delivery_data");
        return CustomerResponse.from(saved);
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append("%02x".formatted(value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
