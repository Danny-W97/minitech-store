package org.demo.orderservice.privacy;

import org.demo.orderservice.privacy.CommerceRetentionGlobalGuardEvidence.GuardInputs;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.privacy.*;
import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Guard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.demo.orderservice.privacy.CommerceRetentionHolds.Reason.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Testcontainers
class CommerceRetentionGlobalGuardsPostgresIT {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("retention_global_guards")
            .withUsername("fixture")
            .withPassword("fixture-only");

    static DriverManagerDataSource ds;
    static DataSourceTransactionManager manager;
    static JdbcTemplate jdbc;

    final JsonMapper json = JsonMapper.builder().build();
    final UUID ledger = UUID.randomUUID();
    KeyPair pair;
    JournalReceiptVerifier verifier;
    CommerceRetentionWorkflow workflow;
    CommerceRetentionJobs jobs;
    CommerceRetentionHolds holds;
    CommerceRetentionGlobalGuardEvidence globalGuards;

    @BeforeAll
    static void setup() {
        ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        manager = new DataSourceTransactionManager(ds);
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("""
            TRUNCATE commerce_retention_holds, commerce_retention_hold_scopes,
                     commerce_retention_payment_evidence, commerce_retention_bindings,
                     commerce_retention_evidence, commerce_retention_jobs,
                     commerce_retention_order_state, account_privacy_fences,
                     commerce_privacy_locks, orders RESTART IDENTITY CASCADE
            """);

        pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = new JournalReceiptVerifier(
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                ledger.toString()
        );
        workflow = new CommerceRetentionWorkflow(ds, manager, verifier);
        jobs = new CommerceRetentionJobs(ds, manager);
        holds = new CommerceRetentionHolds(ds, manager);
        globalGuards = new CommerceRetentionGlobalGuardEvidence(ds, manager, verifier);

        seed(10, 17L);
        seed(11, 18L);
        seed(12, null);
    }

    void seed(long id, Long customer) {
        jdbc.update("""
            INSERT INTO orders(id, customer_id, customer_email, total_price, status,
                               delivery_first_name, delivery_last_name, delivery_street,
                               delivery_postal_code, delivery_city, delivery_country,
                               delivery_phone, delivery_method, delivery_cost,
                               discount_amount, fulfillment_type, idempotency_key,
                               checkout_request_hash, checkout_principal, inventory_reservation_id,
                               created_at, updated_at)
            VALUES(?, ?, 'fixture@example.test', 10, 'CANCELLED', 'Fixture', 'Buyer',
                   'Street', '00-001', 'City', 'PL', '123', 'COURIER', 0, 0,
                   'HOME_DELIVERY', ?, ?, 'fixture', 1, now(), now())
            """, id, customer, "fixture-" + id, "a".repeat(64));
        jdbc.update("INSERT INTO order_items(order_id, product_id, product_name, sku, quantity, unit_price, total_price) VALUES(?, 1, 'Fixture', 'FIX', 1, 10, 10)", id);
        jdbc.update("INSERT INTO order_sagas(order_id, state, created_at, updated_at) VALUES(?, 'FAILED', now(), now())", id);
    }

    Subject prepare(long order) {
        var r = workflow.prepare(order, 0, null, UUID.randomUUID(), Phase.REDACTION);
        assertThat(r.code()).isEqualTo(CommerceRetentionWorkflow.Code.PREPARED);
        return r.subject();
    }

    void bound(Subject s) {
        jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND', bound_epoch=expected_epoch+1, source_revision=0, invalidated=FALSE WHERE order_id=? AND generation=?",
                s.orderId(), s.chainGeneration());
    }

    String signedJournalToken(long customer, UUID req) throws Exception {
        var c = json.createObjectNode();
        c.put("schemaVersion", 1);
        c.put("customerId", Long.toString(customer));
        c.put("requestId", req.toString());
        c.put("confirmedAt", "2026-10-29T00:00:00Z");
        c.put("accountPolicyHash", ErasurePolicies.coreHash());
        c.put("commercePolicyHash", ErasurePolicies.commerceHash());
        c.put("financePolicyHash", FinancePolicy.hash());

        var e = json.createObjectNode();
        e.put("schemaVersion", 1);
        e.put("ledgerId", ledger.toString());
        e.put("sequence", 1);
        e.put("previousHash", "0".repeat(64));
        e.set("capsule", c);

        String frame = "A43.37/entry/1\n" + ledger + "\n1\n" + "0".repeat(64) + "\n" + JournalReceiptVerifier.capsuleHash(c) + "\n";
        e.put("entryHash", Access.hash(frame));

        var signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(frame.getBytes(StandardCharsets.US_ASCII));
        e.put("signature", Base64.getEncoder().encodeToString(signature.sign()));

        return Base64.getUrlEncoder().withoutPadding().encodeToString(e.toString().getBytes(StandardCharsets.UTF_8));
    }

    void populateConfirmedChain(Subject s, Instant reconciledAt) {
        var truncated = reconciledAt.truncatedTo(ChronoUnit.MICROS);
        for (var p : Participant.values()) {
            jobs.record(new ChainProof(p, s, Phase.REDACTION, POLICY, State.CONFIRMED, truncated, null));
        }
    }

    @Test
    void missingEvidenceFailsClosedToUnknownAndWaits() {
        var s = prepare(10);
        bound(s);

        var step = workflow.reviewGlobalGuards(s, Phase.REDACTION, GuardInputs.STRICT_UNAVAILABLE);
        assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.WAITING_DEPENDENCY);

        var states = jdbc.queryForList("SELECT source, state FROM commerce_retention_evidence WHERE kind='GUARD' AND order_id=?",
                s.orderId());
        assertThat(states).isNotEmpty();
        for (var row : states) {
            String source = (String) row.get("source");
            String state = (String) row.get("state");
            assertThat(state).as("Guard %s must fail-closed to UNKNOWN", source).isEqualTo("UNKNOWN");
        }

        populateConfirmedChain(s, Instant.now().minus(400, ChronoUnit.DAYS));
        workflow.reviewFieldScope(s, Phase.REDACTION);

        var assessStep = workflow.assessOne(s, Phase.REDACTION);
        assertThat(assessStep.code()).isEqualTo(CommerceRetentionWorkflow.Code.ASSESSED);
        assertThat(assessStep.assessment().outcome()).isEqualTo(Outcome.WAIT);
        assertThat(assessStep.assessment().code()).isEqualTo(Code.MISSING_GUARD_PROOF);
        assertThat(assessStep.activationAllowed()).isFalse();
        assertThat(assessStep.assessment().activationAllowed()).isFalse();
    }

    @Test
    void activeOrderOrCustomerHoldBlocksGuardReview() {
        var s = prepare(10);
        bound(s);

        holds.placeCustomerPurchases(17, UUID.randomUUID(), Guard.NO_PRESERVATION_HOLD, PRESERVATION_REQUEST);

        var step = workflow.reviewGlobalGuards(s, Phase.REDACTION, GuardInputs.STRICT_UNAVAILABLE);
        assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.BLOCKED);
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?", Boolean.class, s.chainGeneration())).isFalse();
    }

    @Test
    void openCaseOrActiveRetryBlocksGuardReview() {
        var s = prepare(10);
        bound(s);

        var inputs = new GuardInputs(
                null, null,
                (o, c, p) -> State.CONFIRMED,
                (o, c) -> State.BLOCKED, // Open case detected!
                (o, c) -> State.CONFIRMED
        );

        var step = workflow.reviewGlobalGuards(s, Phase.REDACTION, inputs);
        assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.BLOCKED);
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?", Boolean.class, s.chainGeneration())).isFalse();
    }

    @Test
    void activeOrderSagaBlocksRetryDlqGuard() {
        seed(13, 17L);
        jdbc.update("UPDATE order_sagas SET state='INVENTORY_RESERVATION_PENDING' WHERE order_id=13");
        var s = prepare(13);
        bound(s);

        var inputs = new GuardInputs(
                null, null,
                (o, c, p) -> State.CONFIRMED,
                (o, c) -> State.CONFIRMED,
                (o, c) -> State.CONFIRMED
        );

        var step = workflow.reviewGlobalGuards(s, Phase.REDACTION, inputs);
        assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.BLOCKED);
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?", Boolean.class, s.chainGeneration())).isFalse();
    }

    @Test
    void signedInclusionAndUnscopedConfirmationsCannotEstablishGlobalClearance() throws Exception {
        var s = prepare(10);
        bound(s);

        UUID req = UUID.randomUUID();
        String token = signedJournalToken(17L, req);

        assertThat(verifier.verify(token, 17L, req, "commerce").path("sequence").asLong()).isEqualTo(1);

        var inputs = new GuardInputs(
                token, req,
                (o, c, p) -> State.CONFIRMED,
                (o, c) -> State.CONFIRMED,
                (o, c) -> State.CONFIRMED
        );

        var fieldStep = workflow.reviewFieldScope(s, Phase.REDACTION);
        assertThat(fieldStep.code()).isEqualTo(CommerceRetentionWorkflow.Code.FIELD_SCOPE_REVIEWED);

        var guardStep = workflow.reviewGlobalGuards(s, Phase.REDACTION, inputs);
        assertThat(guardStep.code()).isEqualTo(CommerceRetentionWorkflow.Code.WAITING_DEPENDENCY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD' AND source<>'FIELD_SCOPE_REVIEWED' AND state='CONFIRMED'", Long.class)).isZero();

        // Populate 8 confirmed chain proofs reconciled 400 days ago (> 365 days)
        Instant reconciledAt = Instant.now().minus(400, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        populateConfirmedChain(s, reconciledAt);

        jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day'");
        var assessStep = workflow.assessOne(s, Phase.REDACTION);

        assertThat(assessStep.code()).isEqualTo(CommerceRetentionWorkflow.Code.ASSESSED);
        assertThat(assessStep.assessment().outcome()).isEqualTo(Outcome.WAIT);
        assertThat(assessStep.assessment().code()).isEqualTo(Code.MISSING_GUARD_PROOF);
        assertThat(assessStep.activationAllowed()).isFalse();
        assertThat(assessStep.assessment().activationAllowed()).isFalse();

        // Now test for an order reconciled 30 days ago (< 365 days)
        var s2 = workflow.prepare(11, 0, null, UUID.randomUUID(), Phase.REDACTION).subject();
        bound(s2);
        workflow.reviewFieldScope(s2, Phase.REDACTION);

        UUID req2 = UUID.randomUUID();
        String token2 = signedJournalToken(18L, req2);
        var inputs2 = new GuardInputs(token2, req2, (o, c, p) -> State.CONFIRMED, (o, c) -> State.CONFIRMED, (o, c) -> State.CONFIRMED);
        workflow.reviewGlobalGuards(s2, Phase.REDACTION, inputs2);

        Instant recentReconciliation = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        populateConfirmedChain(s2, recentReconciliation);

        jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day' WHERE order_id=11");
        var recentAssess = workflow.assessOne(s2, Phase.REDACTION);
        assertThat(recentAssess.code()).isEqualTo(CommerceRetentionWorkflow.Code.ASSESSED);
        assertThat(recentAssess.assessment().outcome()).isEqualTo(Outcome.WAIT);
        assertThat(recentAssess.assessment().code()).isEqualTo(Code.MISSING_GUARD_PROOF);
        assertThat(recentAssess.activationAllowed()).isFalse();
        assertThat(recentAssess.assessment().activationAllowed()).isFalse();
    }

    @Test
    void canonicalPendingStatesAndUnfinishedSagaMarkersBlockEvenWithPositiveCallbacks() {
        int id = 20;
        for (String change : java.util.List.of(
                "state='WAITING_FOR_PAYMENT'", "state='COMPENSATION_PENDING'",
                "state='RECONCILIATION_REQUIRED'", "state='UNRECOGNIZED'",
                "next_attempt_at=now()", "processing_token='fixture-token'",
                "processing_until=now()-interval '1 day'")) {
            seed(id, 17L);
            jdbc.update("UPDATE order_sagas SET " + change + " WHERE order_id=?", id);
            var subject = prepare(id++);
            var result = globalGuards.collect(subject, Phase.REDACTION,
                    new GuardInputs(null, null, (o,c,p)->State.CONFIRMED, (o,c)->State.CONFIRMED, (o,c)->State.CONFIRMED));
            assertThat(result.code()).as(change).isEqualTo(CommerceRetentionGlobalGuardEvidence.Code.BLOCKED);
            assertThat(result.states().get(Guard.NO_ACTIVE_RETRY_OR_DLQ)).isEqualTo(State.BLOCKED);
        }
    }

    @Test
    void providerFailuresAreUnknownAndCallsRunOutsideSqlTransactions() {
        var s = prepare(10);
        java.util.concurrent.atomic.AtomicInteger called = new java.util.concurrent.atomic.AtomicInteger();
        var result = globalGuards.collect(s, Phase.REDACTION, new GuardInputs(null, null,
                (o,c,p)-> { assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); called.incrementAndGet(); throw new IllegalStateException("fixture unavailable"); },
                (o,c)-> { assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); called.incrementAndGet(); return null; },
                (o,c)-> { assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); called.incrementAndGet(); return State.CONFIRMED; }));
        assertThat(called.get()).isEqualTo(3);
        assertThat(result.code()).isEqualTo(CommerceRetentionGlobalGuardEvidence.Code.WAITING_PROOF);
        assertThat(result.states().values()).containsOnly(State.UNKNOWN);
    }

    @Test
    void customerHoldArrivingDuringCollectionDiscardsAllCollectedProofs() {
        var s = prepare(10);
        var result = globalGuards.collect(s, Phase.REDACTION, new GuardInputs(null, null,
                (o,c,p)-> { holds.placeCustomerPurchases(c, UUID.randomUUID(), Guard.NO_PRESERVATION_HOLD, PRESERVATION_REQUEST); return State.CONFIRMED; }, null, null));
        assertThat(result.code()).isEqualTo(CommerceRetentionGlobalGuardEvidence.Code.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE generation=?", Long.class, s.chainGeneration())).isZero();
    }

    @Test
    void changedSagaDuringCollectionCannotPersistProofsForOldGeneration() {
        var s = prepare(10);
        var result = globalGuards.collect(s, Phase.REDACTION, new GuardInputs(null, null,
                (o,c,p)-> { jdbc.update("UPDATE order_sagas SET state='COMPENSATION_PENDING' WHERE order_id=?", o); return State.CONFIRMED; }, null, null));
        assertThat(result.code()).isEqualTo(CommerceRetentionGlobalGuardEvidence.Code.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE generation=?", Long.class, s.chainGeneration())).isZero();
    }

    @Test
    void contactAndItemTextChangesDuringCollectionInvalidateTheGeneration() {
        int id=30;
        for(String change:java.util.List.of("UPDATE orders SET customer_email='changed@fixture.test' WHERE id=?",
                "UPDATE order_items SET product_name='Changed fixture' WHERE order_id=?")) {
            seed(id,17L);var s=prepare(id++);
            var result=globalGuards.collect(s,Phase.REDACTION,new GuardInputs(null,null,
                    (o,c,p)-> { jdbc.update(change,o);return State.UNKNOWN; },null,null));
            assertThat(result.code()).isEqualTo(CommerceRetentionGlobalGuardEvidence.Code.STALE);
            assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,s.chainGeneration())).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE generation=?",Long.class,s.chainGeneration())).isZero();
        }
    }

    @Test
    void concurrentProofRevisionCannotBeBorrowedByCollection() {
        var s = prepare(10);
        var result = globalGuards.collect(s, Phase.REDACTION, new GuardInputs(null, null,
                (o,c,p)-> { jobs.record(new ChainProof(Participant.ORDER,s,Phase.REDACTION,POLICY,State.UNKNOWN,null,null)); return State.UNKNOWN; }, null, null));
        assertThat(result.code()).isEqualTo(CommerceRetentionGlobalGuardEvidence.Code.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE generation=? AND kind='GUARD'", Long.class, s.chainGeneration())).isZero();
    }

    @Test
    void callerTransactionIsRejectedBeforeAnyProviderCall() {
        var s = prepare(10);
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(status -> globalGuards.collect(s, Phase.REDACTION, GuardInputs.STRICT_UNAVAILABLE)))
                .hasMessage("RETENTION_GUARD_CALLER_TRANSACTION");
    }

    @Test
    void minimumRemovalPhasePermanentlyBlocksProtectedDependencies() throws Exception {
        seed(13, 17L);
        var s = jobs.prepare(13, Phase.MINIMUM_REMOVAL);

        UUID req = UUID.randomUUID();
        String token = signedJournalToken(17L, req);
        var inputs = new GuardInputs(
                token, req,
                (o, c, p) -> State.CONFIRMED,
                (o, c) -> State.CONFIRMED,
                (o, c) -> State.CONFIRMED
        );

        var result = globalGuards.collect(s, Phase.MINIMUM_REMOVAL, inputs);
        assertThat(result.code()).isEqualTo(CommerceRetentionGlobalGuardEvidence.Code.BLOCKED);
        assertThat(result.states().get(Guard.FINANCIAL_DEPENDENCIES_RELEASED)).isEqualTo(State.BLOCKED);
        assertThat(result.states().get(Guard.RESTORE_DEPENDENCIES_RELEASED)).isEqualTo(State.BLOCKED);
        assertThat(result.states().get(Guard.RETRY_DEPENDENCIES_RELEASED)).isEqualTo(State.BLOCKED);
        assertThat(result.states().get(Guard.DEDUP_DEPENDENCIES_RELEASED)).isEqualTo(State.BLOCKED);
    }
}
