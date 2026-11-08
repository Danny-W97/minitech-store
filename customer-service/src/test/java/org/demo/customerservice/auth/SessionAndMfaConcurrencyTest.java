package org.demo.customerservice.auth;

import org.demo.customerservice.audit.AuditService;
import org.demo.customerservice.customer.*;
import org.demo.customerservice.mfa.*;
import org.demo.customerservice.web.MfaRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "customer.auth.mfa-encryption-key-material=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("test")
@Import({AuthService.class, MfaService.class, TotpService.class, TotpQrCodeService.class, MfaEncryptionService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionAndMfaConcurrencyTest {
    @Autowired AuthService auth;
    @Autowired MfaService mfa;
    @Autowired CustomerRepository customers;
    @Autowired RefreshTokenRepository tokens;
    @Autowired TotpMethodRepository methods;
    @Autowired RecoveryCodeRepository recovery;
    @Autowired TotpService totp;
    @Autowired MfaEncryptionService encryption;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AuditService audit;
    @MockitoBean JwtService jwt;
    @MockitoBean PasswordEncoder passwords;
    @MockitoBean LoginProtectionService protection;
    @MockitoBean WebAuthnService webAuthn;
    private final Map<String, String> challenges = new ConcurrentHashMap<>();
    private Customer customer;
    private String secret;

    @BeforeEach @SuppressWarnings("unchecked") void setUp() {
        tokens.deleteAll(); recovery.deleteAll(); methods.deleteAll(); customers.deleteAll();
        customer = new Customer("concurrent@example.test", "hash", "Test", "User", "Testowa 1", "00-001", "Warszawa", "Polska");
        customer.confirmVerification(); customer = customers.saveAndFlush(customer);
        secret = totp.generateSecret(); challenges.clear();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenAnswer(call -> challenges.remove(call.getArgument(0)));
        doAnswer(call -> { challenges.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        when(jwt.createAccessToken(any(), anyString())).thenReturn("test-access");
        when(jwt.accessTokenTtlSeconds()).thenReturn(900L);
    }

    private RefreshToken seedToken(String raw) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        return tokens.saveAndFlush(new RefreshToken(customer.getId(), hash, Instant.now().plusSeconds(3600)));
    }
    private TotpMethod seedTotp(boolean enabled, Instant expiry) {
        var method = new TotpMethod(customer.getId(), encryption.encrypt(secret));
        if (enabled) method.enable(); else method.enrollmentExpiresAt(expiry);
        return methods.saveAndFlush(method);
    }
    private MfaRequests.LoginCode challenge(String id, String code) {
        challenges.put("customer:mfa:login:" + id, customer.getId().toString());
        return new MfaRequests.LoginCode(id, code);
    }
    private String currentCode() { return totp.codeForStep(secret, Instant.now().getEpochSecond() / 30); }
    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (var action : List.of(first, second)) futures.add(executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                try { return action.get(); } catch (RuntimeException exception) { return exception; }
            }));
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test void refreshRaceCreatesOneSuccessAndRevokesTheCompromisedFamily() throws Exception {
        var root = seedToken("refresh-race");
        var results = race(() -> auth.refresh("refresh-race"), () -> auth.refresh("refresh-race"));
        assertThat(results.stream().filter(AuthSession.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(InvalidRefreshTokenException.class::isInstance).count()).isEqualTo(1);
        assertThat(tokens.count()).isEqualTo(2);
        assertThat(tokens.findByCustomerIdAndRevokedAtIsNull(customer.getId())).isEmpty();
        assertThat(tokens.findById(root.getId()).orElseThrow().getRotatedAt()).isNotNull();
    }
    @Test void replayRevocationCommitsAndLeavesAnotherLoginFamilyUsable() throws Exception {
        var first = seedToken("first"); var independent = seedToken("other-login");
        var rotated = auth.refresh("first");
        assertThatThrownBy(() -> auth.refresh("first")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> auth.refresh(rotated.rawRefreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(tokens.findByCustomerIdAndRevokedAtIsNull(customer.getId())).singleElement()
                .satisfies(token -> assertThat(token.getFamilyId()).isEqualTo(independent.getFamilyId()).isNotEqualTo(first.getFamilyId()));
        assertThat(auth.refresh("other-login")).isNotNull();
    }
    @Test void logoutOfARotatedTokenAlsoRevokesItsDescendant() throws Exception {
        seedToken("logout"); var next = auth.refresh("logout");
        auth.logout("logout");
        assertThatThrownBy(() -> auth.refresh(next.rawRefreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }
    @Test void sameTotpAcrossTwoDifferentChallengesCanOnlyBeUsedOnce() throws Exception {
        seedTotp(true, null);
        var a = challenge("totp-a", currentCode()); var b = challenge("totp-b", a.code());
        var result = race(() -> mfa.verifyLoginChallenge(a), () -> mfa.verifyLoginChallenge(b));
        assertThat(result.stream().filter(Customer.class::isInstance).count()).isEqualTo(1);
        assertThat(result.stream().filter(InvalidMfaCodeException.class::isInstance).count()).isEqualTo(1);
        assertThat(methods.findByCustomerId(customer.getId()).orElseThrow().getLastUsedTimeStep()).isNotNull();
    }
    @Test void recoveryCodeAcrossTwoChallengesCanOnlyBeUsedOnce() throws Exception {
        seedTotp(true, null);
        recovery.saveAndFlush(new RecoveryCode(customer.getId(), totp.hashRecoveryCode("AAAA-BBBB")));
        var a = challenge("recovery-a", "AAAA-BBBB"); var b = challenge("recovery-b", "AAAA-BBBB");
        var result = race(() -> mfa.verifyLoginChallenge(a), () -> mfa.verifyLoginChallenge(b));
        assertThat(result.stream().filter(Customer.class::isInstance).count()).isEqualTo(1);
        assertThat(result.stream().filter(InvalidMfaCodeException.class::isInstance).count()).isEqualTo(1);
        assertThat(recovery.findByCustomerIdAndUsedAtIsNull(customer.getId())).isEmpty();
    }
    @Test void expiredEnrollmentInDatabaseIsRejectedDespiteACorrectTotp() {
        seedTotp(false, Instant.now().minusSeconds(1));
        challenges.put("customer:mfa:enrollment:" + customer.getId(), secret);
        assertThatThrownBy(() -> mfa.confirmTotpEnrollment(customer.getId(), customer.getEmail(), currentCode()))
                .isInstanceOf(MfaEnrollmentExpiredException.class);
        assertThat(methods.findByCustomerId(customer.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(recovery.count()).isZero();
    }
    @Test void missingRedisEnrollmentCannotEnableTotp() {
        seedTotp(false, Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> mfa.confirmTotpEnrollment(customer.getId(), customer.getEmail(), currentCode()))
                .isInstanceOf(MfaEnrollmentExpiredException.class);
        assertThat(methods.findByCustomerId(customer.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(recovery.count()).isZero();
    }
    @Test void simultaneousEnrollmentStartsKeepOneRowAndMatchingPendingSecret() throws Exception {
        var results = race(() -> mfa.beginTotpEnrollment(customer.getId(), customer.getEmail()),
                () -> mfa.beginTotpEnrollment(customer.getId(), customer.getEmail()));
        assertThat(results).allMatch(MfaResponses.TotpEnrollment.class::isInstance);
        assertThat(methods.count()).isEqualTo(1);
        assertThat(encryption.decrypt(methods.findByCustomerId(customer.getId()).orElseThrow().getSecretCiphertext()))
                .isEqualTo(challenges.get("customer:mfa:enrollment:" + customer.getId()));
    }
    @Test void confirmingEnabledEnrollmentCannotRegenerateCodes() {
        seedTotp(false, Instant.now().plusSeconds(600));
        challenges.put("customer:mfa:enrollment:" + customer.getId(), secret);
        var codes = mfa.confirmTotpEnrollment(customer.getId(), customer.getEmail(), currentCode());
        assertThat(codes.codes()).hasSize(8);
        assertThatThrownBy(() -> mfa.confirmTotpEnrollment(customer.getId(), customer.getEmail(), currentCode()))
                .isInstanceOf(MfaAlreadyEnabledException.class);
        assertThat(recovery.count()).isEqualTo(8);
    }
    @Test void regenerationRequiresFreshTotpAndRejectsItsReplay() {
        seedTotp(true, null);
        var regenerated = mfa.regenerateRecoveryCodes(customer.getId(), customer.getEmail(), currentCode());
        assertThat(regenerated.codes()).hasSize(8);
        assertThatThrownBy(() -> mfa.regenerateRecoveryCodes(customer.getId(), customer.getEmail(), currentCode()))
                .isInstanceOf(InvalidMfaCodeException.class);
        assertThat(recovery.findByCustomerIdAndUsedAtIsNull(customer.getId())).hasSize(8);
    }
}
