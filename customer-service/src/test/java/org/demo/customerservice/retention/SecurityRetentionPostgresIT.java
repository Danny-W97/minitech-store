package org.demo.customerservice.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.customerservice.audit.AuditService;
import org.demo.customerservice.auth.*;
import org.demo.customerservice.customer.*;
import org.demo.customerservice.mfa.*;
import org.demo.customerservice.notification.RegistrationNotificationClient;
import org.demo.customerservice.web.CustomerRequests;
import org.demo.customerservice.web.MfaRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.customerservice.retention.SecurityRetentionRepository.Store.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
        "customer.auth.mfa-encryption-key-material=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({SecurityRetentionRepository.class,CustomerService.class,AuthService.class,MfaService.class,TotpService.class,TotpQrCodeService.class,MfaEncryptionService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class SecurityRetentionPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("security_retention_fixture").withUsername("fixture").withPassword("retention-fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired SecurityRetentionRepository retention;
    @Autowired CustomerRepository customers;
    @Autowired CustomerService customerService;
    @Autowired RefreshTokenRepository tokens;
    @Autowired TotpMethodRepository methods;
    @Autowired RecoveryCodeRepository recovery;
    @Autowired AuthService auth;
    @Autowired MfaService mfa;
    @Autowired TotpService totp;
    @Autowired MfaEncryptionService encryption;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AuditService audit;
    @MockitoBean JwtService jwt;
    @MockitoBean PasswordEncoder passwords;
    @MockitoBean LoginProtectionService protection;
    @MockitoBean WebAuthnService webAuthn;
    @MockitoBean RegistrationNotificationClient notifications;
    private final Map<String,String> challenges=new ConcurrentHashMap<>();
    private SecurityRetentionWorker activeWorker;
    private Instant now,cutoff,old;
    private int sequence;
    private Customer customer;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        jdbc.execute("TRUNCATE refresh_tokens,password_reset_tokens,customer_recovery_codes,customer_totp_methods,user_credentials,audit_events,customers RESTART IDENTITY CASCADE");
        now=Instant.now();cutoff=now.minus(Duration.ofDays(7));old=cutoff.minusSeconds(60);sequence=0;
        customer=customer();challenges.clear();
        ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenAnswer(call->challenges.remove(call.getArgument(0)));
        doAnswer(call->{challenges.put(call.getArgument(0),call.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        when(jwt.createAccessToken(any(),anyString())).thenReturn("fixture-access");when(jwt.accessTokenTtlSeconds()).thenReturn(900L);
    }
    private Customer customer() {
        var c=new Customer("retention-"+(++sequence)+"@example.test","fixture","Test","User","Street","00-001","City","PL");
        c.confirmVerification();return customers.saveAndFlush(c);
    }
    private String hash(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private Timestamp time(Instant value) {return value==null?null:Timestamp.from(value);}
    private void refresh(String raw,String family,Instant expiry,Instant revoked,Instant rotated) throws Exception {
        jdbc.update("INSERT INTO refresh_tokens(customer_id,token_hash,expires_at,revoked_at,rotated_at,created_at,auth_method,family_id) VALUES (?,?,?,?,?,?,'pwd',?)",
                customer.getId(),hash(raw),time(expiry),time(revoked),time(rotated),time(old),family);
    }
    private void reset(Customer c,Instant expiry,Instant used) throws Exception {
        jdbc.update("INSERT INTO password_reset_tokens(customer_id,token_hash,expires_at,used_at,created_at) VALUES (?,?,?,?,?)",
                c.getId(),hash("reset-"+(++sequence)),time(expiry),time(used),time(old));
    }
    private void code(String raw,Instant used) {
        jdbc.update("INSERT INTO customer_recovery_codes(customer_id,code_hash,used_at,created_at) VALUES (?,?,?,?)",
                customer.getId(),totp.hashRecoveryCode(raw),time(used),time(old));
    }
    private String method(Customer c,boolean enabled,Instant expiry) {
        String secret=totp.generateSecret();
        jdbc.update("INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,enrollment_expires_at,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                c.getId(),encryption.encrypt(secret),enabled,time(expiry),time(old),time(old));return secret;
    }
    private long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}

    private void verification(Customer c,boolean enabled,String raw,Instant expiry,Instant sent,Instant window,int attempts) throws Exception {
        jdbc.update("UPDATE customers SET enabled=?,verification_token_hash=?,verification_expires_at=?,verification_last_sent_at=?,"
                        + "verification_resend_window_started_at=?,verification_resend_count=?,updated_at=? WHERE id=?",
                enabled,raw==null?null:hash(raw),time(expiry),time(sent),time(window),attempts,time(old),c.getId());
    }
    private String sentVerificationToken() {
        var link=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifications).sendConfirmation(any(Customer.class),link.capture());
        return link.getValue().substring(link.getValue().lastIndexOf('=')+1);
    }

    @Test void expiredVerificationHashesAreClearedWithoutDeletingAccountsOrGuessingUnknownExpiry() throws Exception {
        Customer fresh=customer(),boundary=customer(),unknown=customer();
        verification(customer,false,"expired",old,old,old,3);
        verification(fresh,false,"fresh",now.plusSeconds(300),old,old,1);
        verification(boundary,false,"boundary",cutoff,old,old,1);
        verification(unknown,false,"unknown",null,old,old,1);
        var before=jdbc.queryForMap("SELECT email,password_hash,enabled,street,created_at,updated_at,verification_expires_at,"
                + "verification_resend_count,verification_last_sent_at,verification_resend_window_started_at FROM customers WHERE id=?",customer.getId());
        assertThat(retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,1)).isEqualTo(1);
        assertThat(retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,100)).isZero();
        assertThat(customers.findById(customer.getId()).orElseThrow().getVerificationTokenHash()).isNull();
        assertThat(jdbc.queryForMap("SELECT email,password_hash,enabled,street,created_at,updated_at,verification_expires_at,"
                + "verification_resend_count,verification_last_sent_at,verification_resend_window_started_at FROM customers WHERE id=?",customer.getId())).isEqualTo(before);
        for(Customer kept:List.of(fresh,boundary,unknown))assertThat(customers.findById(kept.getId()).orElseThrow().getVerificationTokenHash()).isNotNull();
        assertThat(count("customers")).isEqualTo(4);
        assertThatThrownBy(()->customerService.verify("expired")).isInstanceOf(InvalidVerificationTokenException.class);
        assertThatThrownBy(()->customerService.register(new CustomerRequests.Register(customer.getEmail(),"fixture-password",
                "Other","User","Other street","00-002","Other city","PL"))).isInstanceOf(PendingRegistrationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_customer_verification_expired_retention',"
                + "'idx_customer_verification_confirmed_retention')",Integer.class)).isEqualTo(2);
    }

    @Test void purgedPendingRegistrationCanResendAndConfirmWithoutBypassingEitherThrottle() throws Exception {
        verification(customer,false,"expired",now.minusSeconds(10),now.minusSeconds(1),now.minusSeconds(120),3);
        assertThat(retention.cleanupBatch(VERIFICATION_EXPIRED,now.minusSeconds(2),100)).isEqualTo(1);
        customerService.resendVerification(new CustomerRequests.ResendVerification(customer.getEmail()));
        verifyNoInteractions(notifications);
        jdbc.update("UPDATE customers SET verification_last_sent_at=? WHERE id=?",time(now.minusSeconds(120)),customer.getId());
        customerService.resendVerification(new CustomerRequests.ResendVerification(customer.getEmail()));
        verifyNoInteractions(notifications);
        jdbc.update("UPDATE customers SET verification_resend_window_started_at=? WHERE id=?",time(now.minusSeconds(7200)),customer.getId());
        customerService.resendVerification(new CustomerRequests.ResendVerification(customer.getEmail()));
        String raw=sentVerificationToken();
        assertThat(raw).isNotBlank().isNotEqualTo("expired");
        assertThat(customers.findById(customer.getId()).orElseThrow().getVerificationResendCount()).isEqualTo(1);
        assertThat(retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,100)).isZero();
        assertThat(customerService.verify(raw).id()).isEqualTo(customer.getId());
        assertThat(customers.findById(customer.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test void confirmedMetadataIsClearedInBatchesIncludingLegacyCountersWithNoTimestamp() throws Exception {
        Customer legacy=customer(),fresh=customer(),boundary=customer();
        verification(customer,true,"legacy-hash",old,old,old,3);
        verification(legacy,true,null,null,null,null,2);
        verification(fresh,true,null,null,now,old,2);
        verification(boundary,true,null,null,cutoff,old,2);
        Timestamp updated=jdbc.queryForObject("SELECT updated_at FROM customers WHERE id=?",Timestamp.class,customer.getId());
        assertThat(retention.cleanupBatch(VERIFICATION_CONFIRMED,cutoff,1)).isEqualTo(1);
        assertThat(retention.cleanupBatch(VERIFICATION_CONFIRMED,cutoff,100)).isEqualTo(1);
        assertThat(retention.cleanupBatch(VERIFICATION_CONFIRMED,cutoff,100)).isZero();
        for(Customer cleared:List.of(customer,legacy)) {
            var c=customers.findById(cleared.getId()).orElseThrow();
            assertThat(c.isEnabled()).isTrue();assertThat(c.getVerificationTokenHash()).isNull();
            assertThat(c.getVerificationExpiresAt()).isNull();assertThat(c.getVerificationLastSentAt()).isNull();
            assertThat(c.getVerificationResendWindowStartedAt()).isNull();assertThat(c.getVerificationResendCount()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT updated_at FROM customers WHERE id=?",Timestamp.class,customer.getId())).isEqualTo(updated);
        assertThat(customers.findById(fresh.getId()).orElseThrow().getVerificationLastSentAt()).isNotNull();
        assertThat(customers.findById(boundary.getId()).orElseThrow().getVerificationResendCount()).isEqualTo(2);
        assertThat(count("customers")).isEqualTo(4);
    }

    @Test void aResendHoldingTheCustomerLockCannotLoseItsNewTokenToCleanup() throws Exception {
        verification(customer,false,"expired",old,old,old,3);
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            customerService.resendVerification(new CustomerRequests.ResendVerification(customer.getEmail()));
            try(var pool=Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(()->retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,100)).get(5,TimeUnit.SECONDS)).isZero();
            } catch(Exception error) {throw new AssertionError(error);}
        });
        assertThat(retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,100)).isZero();
        assertThat(customerService.verify(sentVerificationToken()).id()).isEqualTo(customer.getId());
    }

    @Test void anOlderProfileTransactionCannotRestoreMarkersClearedByMaintenance() throws Exception {
        Customer confirmed=customer();
        verification(customer,false,"expired",old,old,old,3);
        verification(confirmed,true,null,null,old,old,3);
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var pending=customers.findById(customer.getId()).orElseThrow();
            var active=customers.findById(confirmed.getId()).orElseThrow();
            try(var pool=Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(()->retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,100)).get(5,TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(pool.submit(()->retention.cleanupBatch(VERIFICATION_CONFIRMED,cutoff,100)).get(5,TimeUnit.SECONDS)).isEqualTo(1);
            } catch(Exception error) {throw new AssertionError(error);}
            pending.updateAddress("New pending street","00-001","City","PL");
            active.updateAddress("New active street","00-001","City","PL");
        });
        var pending=customers.findById(customer.getId()).orElseThrow();
        var active=customers.findById(confirmed.getId()).orElseThrow();
        assertThat(pending.getVerificationTokenHash()).isNull();assertThat(pending.getVerificationExpiresAt()).isNotNull();
        assertThat(pending.getStreet()).isEqualTo("New pending street");
        assertThat(active.getVerificationLastSentAt()).isNull();assertThat(active.getVerificationResendCount()).isZero();
        assertThat(active.getStreet()).isEqualTo("New active street");
    }

    @Test void lockedVerificationRowsRemainVisibleAsBacklogAndOtherAccountsAreProcessed() throws Exception {
        for(Customer c:List.of(customer,customer(),customer()))verification(c,false,"expired-"+c.getId(),old,old,old,3);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM customers WHERE id=? FOR UPDATE")) {
                statement.setLong(1,customer.getId());
                try(var rows=statement.executeQuery()) {assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);
                    assertThat(pool.submit(()->retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,2)).get(5,TimeUnit.SECONDS)).isZero();
                }
                assertThat(retention.oldestEligible(VERIFICATION_EXPIRED,cutoff)).isBefore(cutoff);
            }
            locked.rollback();
        }
        assertThat(retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,2)).isEqualTo(1);
        assertThat(retention.oldestEligible(VERIFICATION_EXPIRED,cutoff)).isNull();
        assertThat(count("customers")).isEqualTo(3);
    }

    @Test void parallelVerificationCleanupAndBoundedWorkerDrainTheBacklogWithoutDeletingAccounts() throws Exception {
        for(int i=0;i<4;i++) {
            Customer pending=i==0?customer:customer(),confirmed=customer();
            verification(pending,false,"expired-"+i,old,old,old,3);
            verification(confirmed,true,null,null,old,old,3);
        }
        var start=new CountDownLatch(1);int cleared;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,2);});
            var b=pool.submit(()->{start.await();return retention.cleanupBatch(VERIFICATION_EXPIRED,cutoff,2);});start.countDown();
            cleared=a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS);
        }
        assertThat(cleared).isBetween(2,4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE verification_token_hash IS NOT NULL",Integer.class)).isEqualTo(4-cleared);
        var registry=new SimpleMeterRegistry();activeWorker=new SecurityRetentionWorker(retention,SecurityRetentionWorkerTest.properties(true,1),registry,Clock.fixed(now,ZoneOffset.UTC));
        activeWorker.cleanup();
        assertThat(registry.get("customer.security.retention.last.cleared").tag("store","verification_confirmed").gauge().value()).isEqualTo(2);
        assertThat(registry.get("customer.security.retention.pending").tag("store","verification_confirmed").gauge().value()).isEqualTo(1);
        for(int i=0;i<3;i++)activeWorker.cleanup();
        for(var store:List.of(VERIFICATION_EXPIRED,VERIFICATION_CONFIRMED))
            assertThat(registry.get("customer.security.retention.pending").tag("store",store.metricTag()).gauge().value()).isZero();
        assertThat(registry.get("customer.security.retention.cleared").tag("store","verification_confirmed").counter().count()).isEqualTo(4);
        assertThat(registry.get("customer.security.retention.cleared").tag("store","verification_expired").counter().count()).isEqualTo(4-cleared);
        assertThat(count("customers")).isEqualTo(8);
    }

    @Test void aVerificationTokenCanOnlyBeConfirmedOnceAcrossTransactionsAndAccountStillAuthenticates() throws Exception {
        verification(customer,false,"valid",now.plusSeconds(300),old,old,1);
        var start=new CountDownLatch(1);
        Callable<Boolean> confirm=()->{start.await();try {customerService.verify("valid");return true;}catch(InvalidVerificationTokenException expected){return false;}};
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(confirm);var b=pool.submit(confirm);start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(retention.cleanupBatch(VERIFICATION_CONFIRMED,cutoff,100)).isEqualTo(1);
        when(passwords.matches("fixture-password","fixture")).thenReturn(true);
        assertThat(customerService.authenticate(new CustomerRequests.Login(customer.getEmail(),"fixture-password")).getId()).isEqualTo(customer.getId());
        assertThat(count("customers")).isEqualTo(1);
    }

    @Test void confirmationWaitingForAResendRechecksTheReplacedTokenUnderTheCustomerLock() throws Exception {
        verification(customer,false,"old-valid",now.plusSeconds(300),old,old,1);
        var results=new ArrayList<Future<Boolean>>();
        try(var pool=Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                customerService.resendVerification(new CustomerRequests.ResendVerification(customer.getEmail()));
                results.add(pool.submit(()->{try {customerService.verify("old-valid");return true;}catch(InvalidVerificationTokenException expected){return false;}}));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%verification_token_hash%'",Boolean.class);
                    if(waiting)break;
                    try {TimeUnit.MILLISECONDS.sleep(20);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
                }
                assertThat(waiting).as("confirmation must actually wait on the row held by resend").isTrue();
            });
            assertThat(results.getFirst().get(5,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(customers.findById(customer.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(customerService.verify(sentVerificationToken()).id()).isEqualTo(customer.getId());
    }

    @Test void expiredRotatedEvidenceSurvivesWhileTheFamilyLivesAndStillRevokesAReplay() throws Exception {
        refresh("root","live",old,old,old);refresh("child","live",now.plusSeconds(3600),null,null);
        refresh("another","independent",now.plusSeconds(3600),null,null);
        refresh("dead1","closed",old,old,old);refresh("dead2","closed",old,null,null);
        assertThat(retention.cleanupBatch(REFRESH,cutoff,100)).isEqualTo(2);
        assertThat(tokens.findByTokenHash(hash("root"))).isPresent();
        assertThatThrownBy(()->auth.refresh("root")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(tokens.findByTokenHash(hash("child")).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(tokens.findByTokenHash(hash("another")).orElseThrow().getRevokedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_refresh_retention_age','idx_refresh_family_retention','idx_password_reset_retention','idx_recovery_used_retention','idx_totp_inactive_retention')",Integer.class)).isEqualTo(5);
    }
    @Test void familyGraceAndExactTimeBoundariesAreConservative() throws Exception {
        refresh("old","old",old,null,null);refresh("boundary","boundary",cutoff,null,null);
        refresh("recent-revoked","recent",old,now,null);refresh("family-old","recent",old,null,null);
        refresh("recent-rotation","rotated",old,old,now);
        assertThat(retention.cleanupBatch(REFRESH,cutoff,100)).isEqualTo(1);assertThat(count("refresh_tokens")).isEqualTo(4);
        assertThat(retention.oldestEligible(REFRESH,cutoff)).isNull();
    }
    @Test void resetExpiryAndRecentUseAreProtectedByTheSameGracePeriod() throws Exception {
        reset(customer,old,null);reset(customer,old,old);reset(customer,old,now);
        reset(customer,cutoff,null);reset(customer,now.plusSeconds(600),null);
        assertThat(retention.cleanupBatch(PASSWORD_RESET,cutoff,1)).isEqualTo(1);
        assertThat(retention.cleanupBatch(PASSWORD_RESET,cutoff,100)).isEqualTo(1);assertThat(count("password_reset_tokens")).isEqualTo(3);
    }
    @Test void unusedOldRecoveryCodesRemainUsableAfterCleanup() {
        method(customer,true,null);code("RETAINCODE01",null);code("USEDOLD1",old);code("USEDRECENT",now);code("USEDBOUNDARY",cutoff);
        assertThat(retention.cleanupBatch(RECOVERY_USED,cutoff,100)).isEqualTo(1);
        assertThat(recovery.findByCustomerIdAndUsedAtIsNull(customer.getId())).hasSize(1);
        String challenge=mfa.createLoginChallenge(customer);
        assertThat(mfa.verifyLoginChallenge(new MfaRequests.LoginCode(challenge,"RETAINCODE01")).getId()).isEqualTo(customer.getId());
        assertThat(retention.cleanupBatch(RECOVERY_USED,cutoff,100)).isZero();
    }
    @Test void activeAndFreshTotpMethodsAreNeverRemovedWithStaleInactiveRows() {
        method(customer,true,old);Customer stale=customer(),legacy=customer(),fresh=customer(),boundary=customer();
        method(stale,false,old);method(legacy,false,null);method(fresh,false,now.plusSeconds(600));method(boundary,false,cutoff);
        assertThat(retention.cleanupBatch(TOTP_INACTIVE,cutoff,1)).isEqualTo(1);
        assertThat(retention.cleanupBatch(TOTP_INACTIVE,cutoff,100)).isEqualTo(1);
        assertThat(methods.findByCustomerIdAndEnabledTrue(customer.getId())).isPresent();
        assertThat(methods.findByCustomerId(fresh.getId())).isPresent();assertThat(count("customers")).isEqualTo(5);
    }
    @Test void customerLockDefersCleanupAndRenewedEnrollmentCanStillBeConfirmed() {
        method(customer,false,old);
        var tx=new TransactionTemplate(transactions);
        String secret=tx.execute(status->{
            var enrollment=mfa.beginTotpEnrollment(customer.getId(),customer.getEmail());
            try(var pool=Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(()->retention.cleanupBatch(TOTP_INACTIVE,cutoff,100)).get(5,TimeUnit.SECONDS)).isZero();
            } catch(Exception error) {throw new AssertionError(error);}
            return enrollment.secret();
        });
        assertThat(retention.cleanupBatch(TOTP_INACTIVE,cutoff,100)).isZero();
        String code=totp.codeForStep(secret,Instant.now().getEpochSecond()/30);
        assertThat(mfa.confirmTotpEnrollment(customer.getId(),customer.getEmail(),code).codes()).hasSize(8);
    }
    @Test void rowLocksAreSkippedWithoutExceedingTheBatchBudget() throws Exception {
        for(int i=0;i<4;i++)code("old-"+i,old);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.createStatement();var rows=statement.executeQuery("SELECT id FROM customer_recovery_codes ORDER BY id LIMIT 1 FOR UPDATE")) {
                assertThat(rows.next()).isTrue();long id=rows.getLong(1);
                try(var pool=Executors.newSingleThreadExecutor()) {assertThat(pool.submit(()->retention.cleanupBatch(RECOVERY_USED,cutoff,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);}
                assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_recovery_codes WHERE id=?",Integer.class,id)).isEqualTo(1);
            }
            locked.rollback();
        }
        assertThat(retention.cleanupBatch(RECOVERY_USED,cutoff,100)).isEqualTo(2);
    }
    @Test void parallelBatchesAreDisjointAndTheWorkerEventuallyClearsTheBacklog() throws Exception {
        for(int c=0;c<4;c++){Customer owner=c==0?customer:customer();for(int i=0;i<5;i++)reset(owner,old,null);reset(owner,now.plusSeconds(600),null);}
        var start=new CountDownLatch(1);int removed=0;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return retention.cleanupBatch(PASSWORD_RESET,cutoff,2);});
            var b=pool.submit(()->{start.await();return retention.cleanupBatch(PASSWORD_RESET,cutoff,2);});start.countDown();
            removed=a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS);
        }
        assertThat(removed).isBetween(2,4);assertThat(count("password_reset_tokens")).isEqualTo(24-removed);
        var registry=new SimpleMeterRegistry();activeWorker=new SecurityRetentionWorker(retention,SecurityRetentionWorkerTest.properties(true,1),registry,Clock.fixed(now,ZoneOffset.UTC));
        activeWorker.cleanup();assertThat(registry.get("customer.security.retention.pending").tag("store","password_reset").gauge().value()).isEqualTo(1);
        for(int i=0;i<10;i++)activeWorker.cleanup();
        assertThat(count("password_reset_tokens")).isEqualTo(4);
        assertThat(registry.get("customer.security.retention.pending").tag("store","password_reset").gauge().value()).isZero();
    }
}
