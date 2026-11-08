package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionAssessment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Testcontainers
class CommerceRetentionJobsPostgresIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("retention_fixture").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    CommerceRetentionJobs jobs;
    final Instant closed = Instant.now().minus(800, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    final Instant redacted = Instant.now().minus(100, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

    @BeforeAll static void migrate() {
        ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE commerce_retention_evidence,commerce_retention_jobs,orders RESTART IDENTITY CASCADE");
        jobs = new CommerceRetentionJobs(ds, manager);
    }
    void order(long id, Long customer) {
        jdbc.update("""
                INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
                delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,
                discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at)
                VALUES(?,?,'fixture@example.test',123.45,'CANCELLED','Fixture','Buyer','Fixture street','00-001','Fixture city','PL',
                '123456789','STANDARD_COURIER',0,0,'HOME_DELIVERY',?,?,?,now(),now())
                """, id, customer, "fixture-key-" + id, "b".repeat(64), customer == null ? "guest:fixture-" + id : "customer:" + customer);
    }
    ChainProof proof(Subject subject, Phase phase, Participant peer) {
        return new ChainProof(peer, subject, phase, POLICY, State.CONFIRMED, closed,
                phase == Phase.MINIMUM_REMOVAL ? redacted : null);
    }
    void complete(Subject subject, Phase phase) {
        for (var peer : Participant.values()) assertThat(jobs.record(proof(subject, phase, peer))).isEqualTo(CommerceRetentionJobs.Receipt.STORED);
        for (var guard : requiredGuards(phase)) assertThat(guard==Guard.FIELD_SCOPE_REVIEWED
            ?jobs.recordReviewedFieldScope(subject,phase,org.demo.platformsecurity.privacy.CommerceRetentionFieldScope.verifiedDigest())
            :jobs.record(guard, new GuardProof(subject, phase, POLICY,
                guard==Guard.PRESERVATION_POLICY_REVIEWED || guard==Guard.INDEPENDENT_JOURNAL_CURRENT ? State.UNKNOWN : State.CONFIRMED))).isEqualTo(CommerceRetentionJobs.Receipt.STORED);
    }
    // Explicitly due fixture, independent of observed WSL wall-clock adjustments.
    void due() { jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-INTERVAL '1 day' WHERE current"); }

    @Test void migrationAndRestartPreserveExactClocksWithoutRedactingBusinessData() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.REDACTION); complete(subject, Phase.REDACTION);
        var result = jobs.assess(jobs.claim().orElseThrow()).orElseThrow();
        assertThat(result.outcome()).isEqualTo(Outcome.WAIT); assertThat(result.code()).isEqualTo(Code.MISSING_GUARD_PROOF); assertThat(result.activationAllowed()).isFalse();
        jobs = new CommerceRetentionJobs(ds, manager);
        assertThat(jobs.prepare(1, Phase.REDACTION)).isEqualTo(subject);
        assertThat(jobs.record(proof(subject, Phase.REDACTION, Participant.ORDER))).isEqualTo(CommerceRetentionJobs.Receipt.IDEMPOTENT);
        assertThat(jobs.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT eligible_at FROM commerce_retention_jobs", java.sql.Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT reconciled_at FROM commerce_retention_evidence WHERE source='ORDER' AND kind='CHAIN'", java.sql.Timestamp.class).toInstant()).isEqualTo(closed);
        assertThat(jdbc.queryForMap("SELECT customer_email,total_price,privacy_redacted FROM orders WHERE id=1"))
                .containsEntry("customer_email", "fixture@example.test").containsEntry("privacy_redacted", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks", Long.class)).isZero();
    }

    @Test void minimumUsesActualRedactionAndCannotBorrowAnotherPhaseProofs() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.MINIMUM_REMOVAL);
        assertThat(jobs.record(proof(subject, Phase.REDACTION, Participant.ORDER))).isEqualTo(CommerceRetentionJobs.Receipt.STALE);
        complete(subject, Phase.MINIMUM_REMOVAL);
        var result = jobs.assess(jobs.claim().orElseThrow()).orElseThrow();
        assertThat(result.eligibleAt()).isNull();
        assertThat(result.outcome()).isEqualTo(Outcome.WAIT);
        assertThat(result.code()).isEqualTo(Code.MISSING_GUARD_PROOF);
        assertThat(jdbc.queryForObject("SELECT redacted_at FROM commerce_retention_evidence WHERE source='ORDER' AND kind='CHAIN'", java.sql.Timestamp.class).toInstant()).isEqualTo(redacted);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(1);
    }

    @Test void missingProofsWaitRepeatedlyWithoutConsumingFailureBudget() {
        order(1, 17L); jobs.prepare(1, Phase.REDACTION);
        for (int n = 0; n < 10; n++) {
            due(); assertThat(jobs.assess(jobs.claim().orElseThrow()).orElseThrow().code()).isEqualTo(Code.MISSING_CHAIN_PROOF);
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_jobs", String.class)).isEqualTo("WAITING_PROOF");
    }

    @Test void newEvidenceCancelsLeasesAndOldWorkersCannotFinishOrFailNewWork() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.REDACTION); var old = jobs.claim().orElseThrow();
        jobs.record(proof(subject, Phase.REDACTION, Participant.ORDER));
        var current = jobs.claim().orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(jobs.assess(old)).isEmpty(); assertThat(jobs.failed(old)).isFalse();
        assertThat(jobs.assess(current).orElseThrow().code()).isEqualTo(Code.MISSING_CHAIN_PROOF);
    }

    @Test void conflictingReceiptRetiresGenerationAndPreservesOldEvidence() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.REDACTION); var proof = proof(subject, Phase.REDACTION, Participant.ORDER);
        jobs.record(proof); var work = jobs.claim().orElseThrow();
        assertThat(jobs.record(proof)).isEqualTo(CommerceRetentionJobs.Receipt.IDEMPOTENT);
        assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_jobs", UUID.class)).isEqualTo(work.token());
        assertThat(jobs.record(new ChainProof(Participant.ORDER, subject, Phase.REDACTION, POLICY, State.CONFIRMED, closed.plusSeconds(1), null)))
                .isEqualTo(CommerceRetentionJobs.Receipt.CONFLICT);
        assertThat(jobs.assess(work)).isEmpty(); assertThat(jobs.record(proof)).isEqualTo(CommerceRetentionJobs.Receipt.STALE);
        var newer = jobs.prepare(1, Phase.REDACTION);
        assertThat(newer.chainGeneration()).isNotEqualTo(subject.chainGeneration());
        assertThat(jobs.invalidate(1, subject.chainGeneration())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE current", Long.class)).isEqualTo(1);
    }

    @Test void guestProofsRemainOrderScopedAndNoFakeCustomerIsStored() {
        order(1, null); order(2, null);
        var first = jobs.prepare(1, Phase.REDACTION); var second = jobs.prepare(2, Phase.REDACTION);
        assertThat(first.customerId()).isZero();
        var foreign = new Subject(1, 0, first.orderVersion(), second.chainGeneration());
        assertThat(jobs.record(proof(foreign, Phase.REDACTION, Participant.ORDER))).isEqualTo(CommerceRetentionJobs.Receipt.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE customer_id IS NULL", Long.class)).isEqualTo(2);
        complete(first, Phase.REDACTION);
        jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()+INTERVAL '1 day' WHERE order_id=2");
        assertThat(jobs.assess(jobs.claim().orElseThrow()).orElseThrow().outcome()).isEqualTo(Outcome.WAIT);
    }

    @Test void orderItemAndSagaChangesInvalidateCurrentGenerationEvenWithoutVersionIncrement() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.REDACTION); complete(subject, Phase.REDACTION);
        var work = jobs.claim().orElseThrow();
        jdbc.update("UPDATE orders SET customer_email='changed@fixture.test' WHERE id=1");
        assertThat(jobs.assess(work)).isEmpty();
        assertThat(jobs.record(proof(subject, Phase.REDACTION, Participant.ORDER))).isEqualTo(CommerceRetentionJobs.Receipt.STALE);
        var second = jobs.prepare(1, Phase.REDACTION);
        jdbc.update("UPDATE orders SET updated_at=clock_timestamp() WHERE id=1");
        assertThat(jobs.prepare(1, Phase.REDACTION)).isEqualTo(second);
        jdbc.update("INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES(1,'FAILED',now(),now())");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE current", Long.class)).isZero();
        jobs.prepare(1, Phase.REDACTION);
        jdbc.update("INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) VALUES(1,1,'FIXTURE','Fixture',1,123.45,123.45)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE current", Long.class)).isZero();
    }

    @Test void movingAChildInvalidatesBothOldAndNewOrder() {
        order(1, 17L); order(2, 18L);
        jdbc.update("INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) VALUES(1,1,'FIXTURE','Fixture',1,123.45,123.45)");
        jobs.prepare(1, Phase.REDACTION); jobs.prepare(2, Phase.REDACTION);
        jdbc.update("UPDATE order_items SET order_id=2 WHERE order_id=1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE current", Long.class)).isZero();
    }

    @Test void concurrentClaimersOwnDifferentJobsAndExpiredClaimsCannotCommit() throws Exception {
        order(1, 17L); order(2, 18L); jobs.prepare(1, Phase.REDACTION); jobs.prepare(2, Phase.REDACTION);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> jobs.claim().orElseThrow()); var b = pool.submit(() -> jobs.claim().orElseThrow());
            var first = a.get(5, TimeUnit.SECONDS); var second = b.get(5, TimeUnit.SECONDS);
            assertThat(first.subject().orderId()).isNotEqualTo(second.subject().orderId());
            assertThat(jobs.claim()).isEmpty();
            jdbc.update("UPDATE commerce_retention_jobs SET run_until=clock_timestamp()-INTERVAL '1 second' WHERE order_id=?", first.subject().orderId());
            var newer = jobs.claim().orElseThrow();
            assertThat(jobs.assess(first)).isEmpty(); assertThat(jobs.failed(first)).isFalse();
            assertThat(jobs.assess(newer)).isPresent(); assertThat(jobs.assess(second)).isPresent();
        }
    }

    @Test void leaseExpiryWhileWaitingForOrderLockUsesWallClockNotTransactionStart() throws Exception {
        order(1, 17L); jobs.prepare(1, Phase.REDACTION); var work = jobs.claim().orElseThrow();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var locker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM orders WHERE id=1 FOR NO KEY UPDATE", Long.class); locked.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE commerce_retention_jobs SET run_until=clock_timestamp()+INTERVAL '300 milliseconds' WHERE order_id=1");
            var assessment = pool.submit(() -> jobs.assess(work)); Thread.sleep(600); release.countDown();
            locker.get(5, TimeUnit.SECONDS); assertThat(assessment.get(5, TimeUnit.SECONDS)).isEmpty();
        } finally { release.countDown(); }
    }

    @Test void crashedLastLeaseIsRecoveredWithoutAutomaticRetryReset() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.REDACTION);
        for (int n = 0; n < 8; n++) {
            due(); assertThat(jobs.claim()).as("crash attempt %s; state %s; DB now %s",n,jdbc.queryForMap("SELECT state,attempts,next_attempt_at,run_until FROM commerce_retention_jobs WHERE current"),jdbc.queryForObject("SELECT clock_timestamp()::text",String.class)).isPresent();
            // Recovery of an expired crash lease, not a sub-second clock-boundary test.
            jdbc.update("UPDATE commerce_retention_jobs SET run_until=clock_timestamp()-INTERVAL '1 day' WHERE current");
        }
        assertThat(jobs.claim()).isEmpty(); assertThat(jobs.prepare(1, Phase.REDACTION)).isEqualTo(subject);
        assertThat(jobs.claim()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT state,code,attempts FROM commerce_retention_jobs WHERE current"))
                .containsEntry("state", "FAILED").containsEntry("code", "RETRY_LIMIT").containsEntry("attempts", 8);
    }

    @Test void policyPhaseIdentityAndEvidenceConstraintsCannotBeBypassed() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.REDACTION);
        assertThatThrownBy(() -> jobs.prepare(1, Phase.MINIMUM_REMOVAL)).isInstanceOf(IllegalStateException.class);
        var wrongOwner = new Subject(1, 18, subject.orderVersion(), subject.chainGeneration());
        assertThat(jobs.record(proof(wrongOwner, Phase.REDACTION, Participant.ORDER))).isEqualTo(CommerceRetentionJobs.Receipt.STALE);
        assertThatThrownBy(() -> jobs.record(new ChainProof(Participant.ORDER, subject, Phase.REDACTION, "unreviewed", State.CONFIRMED, closed, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO commerce_retention_evidence(order_id,generation,phase,kind,source,policy,state) VALUES(1,?,'REDACTION','GUARD','arbitrary-text',?,'CONFIRMED')", subject.chainGeneration(), POLICY))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence", Long.class)).isZero();
        // This case tests identity, not the scheduler's immediate wall-clock boundary.
        due();
        var work = jobs.claim().orElseThrow();
        var forged = new CommerceRetentionJobs.Work(wrongOwner, work.phase(), work.proofRevision(), work.token());
        assertThat(jobs.failed(forged)).isFalse(); assertThat(jobs.assess(forged)).isEmpty();
        assertThat(jobs.failed(work)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs", Integer.class)).isEqualTo(1);
    }

    @Test void genericWritesCannotConfirmUnresolvedPolicyOrJournalCurrentness() {
        order(1,17L);var s=jobs.prepare(1,Phase.REDACTION);
        for(var guard:java.util.List.of(Guard.PRESERVATION_POLICY_REVIEWED,Guard.INDEPENDENT_JOURNAL_CURRENT))
            assertThatThrownBy(()->jobs.record(guard,new GuardProof(s,Phase.REDACTION,POLICY,State.CONFIRMED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }

    @Test void historicalPositivePolicyAndJournalRowsAreDowngradedAtAssessment() {
        order(1,17L);var s=jobs.prepare(1,Phase.REDACTION);
        for(var peer:Participant.values())jobs.record(proof(s,Phase.REDACTION,peer));
        for(var guard:requiredGuards(Phase.REDACTION)) {
            if(guard==Guard.FIELD_SCOPE_REVIEWED)jobs.recordReviewedFieldScope(s,Phase.REDACTION,org.demo.platformsecurity.privacy.CommerceRetentionFieldScope.verifiedDigest());
            else if(guard==Guard.PRESERVATION_POLICY_REVIEWED || guard==Guard.INDEPENDENT_JOURNAL_CURRENT)
                // Simulate pre-124 immutable history, not a supported write API.
                jdbc.update("INSERT INTO commerce_retention_evidence(order_id,generation,phase,kind,source,policy,state) VALUES(?,?,'REDACTION','GUARD',?,?,'CONFIRMED')",s.orderId(),s.chainGeneration(),guard.name(),POLICY);
            else jobs.record(guard,new GuardProof(s,Phase.REDACTION,POLICY,State.CONFIRMED));
        }
        var result=jobs.assess(jobs.claim().orElseThrow()).orElseThrow();
        assertThat(result.outcome()).isEqualTo(Outcome.WAIT);
        assertThat(result.code()).isEqualTo(Code.MISSING_GUARD_PROOF);
        assertThat(result.activationAllowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE source IN ('PRESERVATION_POLICY_REVIEWED','INDEPENDENT_JOURNAL_CURRENT') AND state='CONFIRMED'",Long.class)).isEqualTo(2);
    }

    @Test void authenticatedCallbackScopeCannotInvalidateAnotherPhaseVersionOrGeneration() {
        order(1, 17L); var subject = jobs.prepare(1, Phase.REDACTION); var work = jobs.claim().orElseThrow();
        assertThat(jobs.invalidate(1, subject.chainGeneration(), subject.orderVersion() + 1, Phase.REDACTION)).isFalse();
        assertThat(jobs.invalidate(1, subject.chainGeneration(), subject.orderVersion(), Phase.MINIMUM_REMOVAL)).isFalse();
        assertThat(jobs.invalidate(1, UUID.randomUUID(), subject.orderVersion(), Phase.REDACTION)).isFalse();
        assertThat(jobs.invalidate(99, subject.chainGeneration(), subject.orderVersion(), Phase.REDACTION)).isFalse();
        assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_jobs WHERE current", UUID.class)).isEqualTo(work.token());
        assertThat(jobs.invalidate(1, subject.chainGeneration(), subject.orderVersion(), Phase.REDACTION)).isTrue();
        assertThat(jobs.assess(work)).isEmpty();
        var newer = jobs.prepare(1, Phase.REDACTION);
        assertThat(jobs.invalidate(1, subject.chainGeneration(), subject.orderVersion(), Phase.REDACTION)).isFalse();
        assertThat(jobs.prepare(1, Phase.REDACTION)).isEqualTo(newer);
    }
}
