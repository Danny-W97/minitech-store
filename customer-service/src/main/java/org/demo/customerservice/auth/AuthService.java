package org.demo.customerservice.auth;

import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.customer.CustomerNotFoundException;
import org.demo.customerservice.customer.CustomerRepository;
import org.demo.customerservice.customer.InvalidCredentialsException;
import org.demo.customerservice.audit.AuditService;
import org.demo.customerservice.mfa.MfaService;
import org.demo.customerservice.mfa.WebAuthnService;
import org.demo.customerservice.web.CustomerRequests;
import org.demo.customerservice.web.MfaRequests;
import org.demo.customerservice.web.CustomerResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Service
public class AuthService {

    private final CustomerRepository customerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginProtectionService loginProtectionService;
    private final AuditService auditService;
    private final MfaService mfaService;
    private final WebAuthnService webAuthnService;
    private final long refreshTokenTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            CustomerRepository customerRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginProtectionService loginProtectionService,
            AuditService auditService,
            MfaService mfaService,
            WebAuthnService webAuthnService,
            @Value("${customer.auth.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds
    ) {
        this.customerRepository = customerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginProtectionService = loginProtectionService;
        this.auditService = auditService;
        this.mfaService = mfaService;
        this.webAuthnService = webAuthnService;
        if (refreshTokenTtlSeconds < 300) {
            throw new IllegalStateException("customer.auth.refresh-token-ttl-seconds must be at least 300");
        }
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @Transactional
    public AuthLoginResult login(CustomerRequests.Login request) {
        String email = normalize(request.email());
        try {
            loginProtectionService.assertNotLocked(email);
        } catch (AccountLockedException exception) {
            auditService.record("LOGIN_BLOCKED", email, "CUSTOMER", "REJECTED",
                    "retry_after_seconds=" + exception.getRetryAfterSeconds());
            throw exception;
        }

        Customer customer = customerRepository.findByEmail(email).orElse(null);
        if (customer == null || !customer.isEnabled()
                || !passwordEncoder.matches(request.password(), customer.getPasswordHash())) {
            boolean locked = loginProtectionService.recordFailure(email);
            auditService.record(locked ? "LOGIN_LOCKED" : "LOGIN_FAILURE", email, "CUSTOMER", "REJECTED",
                    locked ? "lock_seconds=" + loginProtectionService.lockSeconds() : "");
            throw new InvalidCredentialsException();
        }
        loginProtectionService.clearFailures(email);
        auditService.record("LOGIN_SUCCESS", email, "CUSTOMER", "SUCCESS", "");
        if (mfaService.isTotpEnabled(customer.getId())) {
            String challengeId = mfaService.createLoginChallenge(customer);
            auditService.record("MFA_LOGIN_CHALLENGE", customer.getEmail(), "MFA", "PENDING", "totp");
            return AuthLoginResult.mfaRequired(new MfaLoginChallenge(true, challengeId, 300, "totp"));
        }
        if (webAuthnService.hasCredentials(customer.getId())) {
            String challengeId = webAuthnService.createLoginChallenge(customer);
            auditService.record("MFA_LOGIN_CHALLENGE", customer.getEmail(), "MFA", "PENDING", "webauthn");
            return AuthLoginResult.mfaRequired(new MfaLoginChallenge(true, challengeId, 300, "webauthn"));
        }
        return AuthLoginResult.authenticated(issueSession(customer, "pwd"));
    }

    @Transactional
    public AuthSession completeMfaLogin(MfaRequests.LoginCode request) {
        Customer customer = mfaService.verifyLoginChallenge(request);
        return issueSession(customer, "pwd+totp");
    }

    @Transactional
    public AuthSession completeWebAuthnLogin(MfaRequests.WebAuthnLogin request) {
        Customer customer = webAuthnService.verifyLogin(request.challengeId(), new WebAuthnService.WebAuthnCredentialRequest(
                request.id(), request.type(), request.rawId(),
                new WebAuthnService.ClientResponse(
                        request.response().clientDataJSON(), null,
                        request.response().authenticatorData(), request.response().signature(),
                        request.response().userHandle()
                )
        ));
        return issueSession(customer, "pwd+webauthn");
    }

    @Transactional
    public AuthSession refresh(String rawRefreshToken) {
        try {
            RefreshToken current = findUsable(rawRefreshToken);
            Customer customer = customerRepository.findById(current.getCustomerId())
                    .filter(Customer::isEnabled)
                    .orElseThrow(InvalidRefreshTokenException::new);
            current.revoke();
            refreshTokenRepository.save(current);
            auditService.record("TOKEN_REFRESH", String.valueOf(customer.getId()), "REFRESH_TOKEN", "SUCCESS", "rotated=true");
            return issueSession(customer, current.getAuthenticationMethod());
        } catch (InvalidRefreshTokenException exception) {
            auditService.record("TOKEN_REFRESH", "anonymous", "REFRESH_TOKEN", "REJECTED", "invalid_or_expired");
            throw exception;
        }
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });
        auditService.record("LOGOUT", "anonymous", "REFRESH_TOKEN", "SUCCESS", "");
    }

    public java.util.Map<String, Object> jwks() {
        return jwtService.jwks();
    }

    private AuthSession issueSession(Customer customer, String authenticationMethod) {
        CustomerPrincipal principal = new CustomerPrincipal(customer.getId(), customer.getEmail());
        String accessToken = jwtService.createAccessToken(principal, authenticationMethod);
        String rawRefreshToken = generateRefreshToken();
        Instant refreshExpiresAt = Instant.now().plusSeconds(refreshTokenTtlSeconds);
        refreshTokenRepository.save(new RefreshToken(
                customer.getId(), hashToken(rawRefreshToken), refreshExpiresAt, authenticationMethod
        ));
        return new AuthSession(
                new AuthResponse(
                        accessToken,
                        "Bearer",
                        jwtService.accessTokenTtlSeconds(),
                        refreshTokenTtlSeconds,
                        CustomerResponse.from(customer)
                ),
                rawRefreshToken
        );
    }

    private RefreshToken findUsable(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        RefreshToken token = refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        if (!token.isUsable(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }
        return token;
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
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

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
