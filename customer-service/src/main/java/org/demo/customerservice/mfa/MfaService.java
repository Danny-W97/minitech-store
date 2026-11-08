package org.demo.customerservice.mfa;

import org.demo.customerservice.audit.AuditService;
import org.demo.customerservice.auth.MfaChallengeExpiredException;
import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.customer.CustomerRepository;
import org.demo.customerservice.web.MfaRequests;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MfaService {

    private static final Duration LOGIN_CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Duration ENROLLMENT_TTL = Duration.ofMinutes(10);
    private final CustomerRepository customerRepository;
    private final TotpMethodRepository totpMethodRepository;
    private final RecoveryCodeRepository recoveryCodeRepository;
    private final StringRedisTemplate redis;
    private final TotpService totpService;
    private final TotpQrCodeService qrCodeService;
    private final MfaEncryptionService encryptionService;
    private final AuditService auditService;

    public MfaService(
            CustomerRepository customerRepository,
            TotpMethodRepository totpMethodRepository,
            RecoveryCodeRepository recoveryCodeRepository,
            StringRedisTemplate redis,
            TotpService totpService,
            TotpQrCodeService qrCodeService,
            MfaEncryptionService encryptionService,
            AuditService auditService
    ) {
        this.customerRepository = customerRepository;
        this.totpMethodRepository = totpMethodRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.redis = redis;
        this.totpService = totpService;
        this.qrCodeService = qrCodeService;
        this.encryptionService = encryptionService;
        this.auditService = auditService;
    }

    public boolean isTotpEnabled(Long customerId) {
        return totpMethodRepository.findByCustomerIdAndEnabledTrue(customerId).isPresent();
    }

    public String createLoginChallenge(Customer customer) {
        String challengeId = UUID.randomUUID().toString();
        redis.opsForValue().set(loginKey(challengeId), String.valueOf(customer.getId()), LOGIN_CHALLENGE_TTL);
        return challengeId;
    }

    @Transactional
    public Customer verifyLoginChallenge(MfaRequests.LoginCode request) {
        String storedCustomerId = redis.opsForValue().getAndDelete(loginKey(request.challengeId()));
        if (storedCustomerId == null) {
            throw new MfaChallengeExpiredException();
        }
        Customer customer = lockCustomer(Long.valueOf(storedCustomerId));
        TotpMethod method = totpMethodRepository.findByCustomerIdAndEnabledTrue(customer.getId())
                .orElseThrow(MfaChallengeExpiredException::new);
        if (!verifyAndConsumeCode(customer.getId(), method, request.code())) {
            auditService.recordForCustomer(customer.getId(),"MFA_LOGIN_FAILURE", customer.getEmail(), "MFA", "REJECTED", "totp_or_recovery_code");
            throw new InvalidMfaCodeException();
        }
        auditService.recordForCustomer(customer.getId(),"MFA_LOGIN_SUCCESS", customer.getEmail(), "MFA", "SUCCESS", "totp_or_recovery_code");
        return customer;
    }

    @Transactional
    public MfaResponses.TotpEnrollment beginTotpEnrollment(Long customerId, String email) {
        lockCustomer(customerId);
        TotpMethod existing = totpMethodRepository.findByCustomerId(customerId).orElse(null);
        if (existing != null && existing.isEnabled()) {
            throw new MfaAlreadyEnabledException();
        }
        String secret = totpService.generateSecret();
        if (existing != null) {
            // Keep the unique customer_id row and replace the unfinished
            // enrollment in place. Delete + insert may be flushed in the
            // wrong order by Hibernate and violate the unique constraint.
            existing.restartEnrollment(encryptionService.encrypt(secret));
        } else {
            existing = new TotpMethod(customerId, encryptionService.encrypt(secret));
        }
        existing.enrollmentExpiresAt(Instant.now().plus(ENROLLMENT_TTL));
        totpMethodRepository.save(existing);
        String otpauthUri = totpService.otpauthUri(email, "MiniTech Store", secret);
        redis.opsForValue().set(enrollmentKey(customerId), secret, ENROLLMENT_TTL);
        auditService.recordForCustomer(customerId,"MFA_TOTP_ENROLLMENT_STARTED", email, "MFA", "SUCCESS", "secret_not_logged");
        return new MfaResponses.TotpEnrollment(
                secret,
                otpauthUri,
                qrCodeService.createPngDataUri(otpauthUri),
                ENROLLMENT_TTL.toSeconds()
        );
    }

    @Transactional
    public MfaResponses.RecoveryCodes confirmTotpEnrollment(Long customerId, String email, String code) {
        lockCustomer(customerId);
        TotpMethod method = totpMethodRepository.findByCustomerId(customerId)
                .orElseThrow(InvalidMfaCodeException::new);
        if (method.isEnabled()) throw new MfaAlreadyEnabledException();
        if (method.getEnrollmentExpiresAt() == null || !method.getEnrollmentExpiresAt().isAfter(Instant.now())) {
            throw new MfaEnrollmentExpiredException();
        }
        String secret = encryptionService.decrypt(method.getSecretCiphertext());
        TotpService.Verification verification = totpService.verify(
                secret, code, Instant.now().getEpochSecond(), method.getLastUsedTimeStep()
        );
        if (!verification.valid()) {
            throw new InvalidMfaCodeException();
        }
        String pending = redis.opsForValue().getAndDelete(enrollmentKey(customerId));
        if (pending == null || !java.security.MessageDigest.isEqual(
                pending.getBytes(java.nio.charset.StandardCharsets.UTF_8), secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new MfaEnrollmentExpiredException();
        }
        method.markUsed(verification.timeStep());
        method.enable();
        totpMethodRepository.save(method);
        MfaResponses.RecoveryCodes recoveryCodes = issueRecoveryCodes(customerId);
        auditService.recordForCustomer(customerId,"MFA_TOTP_ENABLED", email, "MFA", "SUCCESS", "recovery_codes_issued=8");
        return recoveryCodes;
    }

    @Transactional
    public void disableTotp(Long customerId, String email, String code) {
        lockCustomer(customerId);
        TotpMethod method = totpMethodRepository.findByCustomerIdAndEnabledTrue(customerId)
                .orElseThrow(InvalidMfaCodeException::new);
        if (!verifyAndConsumeCode(customerId, method, code)) {
            throw new InvalidMfaCodeException();
        }
        method.disable();
        totpMethodRepository.save(method);
        recoveryCodeRepository.deleteByCustomerId(customerId);
        auditService.recordForCustomer(customerId,"MFA_TOTP_DISABLED", email, "MFA", "SUCCESS", "");
    }

    @Transactional
    public MfaResponses.RecoveryCodes regenerateRecoveryCodes(Long customerId, String email, String code) {
        lockCustomer(customerId);
        TotpMethod method = totpMethodRepository.findByCustomerIdAndEnabledTrue(customerId)
                .orElseThrow(InvalidMfaCodeException::new);
        if (!verifyAndConsumeTotp(method, code)) throw new InvalidMfaCodeException();
        var result = issueRecoveryCodes(customerId);
        auditService.recordForCustomer(customerId,"MFA_RECOVERY_CODES_REGENERATED", email, "MFA", "SUCCESS", "totp_confirmed=true");
        return result;
    }

    private MfaResponses.RecoveryCodes issueRecoveryCodes(Long customerId) {
        recoveryCodeRepository.deleteByCustomerId(customerId);
        List<String> rawCodes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String rawCode = totpService.generateRecoveryCode();
            rawCodes.add(rawCode);
            recoveryCodeRepository.save(new RecoveryCode(customerId, totpService.hashRecoveryCode(rawCode)));
        }
        return new MfaResponses.RecoveryCodes(rawCodes);
    }

    public MfaResponses.Status status(Long customerId, boolean webAuthnAvailable, int webAuthnCredentials) {
        return new MfaResponses.Status(
                isTotpEnabled(customerId),
                webAuthnAvailable,
                recoveryCodeRepository.findByCustomerIdAndUsedAtIsNull(customerId).size()
        );
    }

    private boolean verifyAndConsumeCode(Long customerId, TotpMethod method, String rawCode) {
        String normalized = rawCode == null ? "" : rawCode.trim();
        if (verifyAndConsumeTotp(method, normalized)) return true;
        return recoveryCodeRepository.findByCustomerIdAndCodeHashAndUsedAtIsNull(
                        customerId, totpService.hashRecoveryCode(normalized.toUpperCase(Locale.ROOT))
                )
                .map(code -> {
                    code.markUsed();
                    recoveryCodeRepository.save(code);
                    return true;
                })
                .orElse(false);
    }

    private boolean verifyAndConsumeTotp(TotpMethod method, String rawCode) {
        String secret = encryptionService.decrypt(method.getSecretCiphertext());
        TotpService.Verification verification = totpService.verify(
                secret, rawCode == null ? "" : rawCode.trim(), Instant.now().getEpochSecond(), method.getLastUsedTimeStep()
        );
        if (verification.valid()) {
            method.markUsed(verification.timeStep());
            totpMethodRepository.save(method);
            return true;
        }
        return false;
    }

    private Customer lockCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId).filter(Customer::isEnabled)
                .orElseThrow(MfaChallengeExpiredException::new);
    }

    private String loginKey(String challengeId) {
        return "customer:mfa:login:" + challengeId;
    }

    private String enrollmentKey(Long customerId) {
        return "customer:mfa:enrollment:" + customerId;
    }
}
