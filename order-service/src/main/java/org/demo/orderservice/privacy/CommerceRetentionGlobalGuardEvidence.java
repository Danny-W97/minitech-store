package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import org.demo.platformsecurity.privacy.JournalReceiptVerifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.POLICY;

/** Order-local negative observations, never global clearance or execution authority.
 * Legacy unscoped callbacks may report blockers, but cannot confirm absence.
 * Collection releases SQL locks before invoking a provider and rechecks the exact
 * generation, proof revision, process revision and hold scopes before persisting.
 */
public final class CommerceRetentionGlobalGuardEvidence {
    public enum Code { RECORDED, STALE, BLOCKED, WAITING_PROOF, UNAVAILABLE }
    @FunctionalInterface
    public interface GlobalHoldProvider { State checkGlobalHold(long orderId, long customerId, Phase phase); }
    @FunctionalInterface
    public interface CaseProvider { State checkCases(long orderId, long customerId); }
    @FunctionalInterface
    public interface RetryDlqProvider { State checkRetryDlq(long orderId, long customerId); }

    /** Compatibility inputs lack coverage, source revision and freshness: no positive proofs.
     * A journal inclusion token alone cannot establish the current independent head. */
    public record GuardInputs(String journalReceiptToken, UUID journalRequestId,
                              GlobalHoldProvider holdProvider, CaseProvider caseProvider,
                              RetryDlqProvider retryDlqProvider) {
        public static final GuardInputs STRICT_UNAVAILABLE = new GuardInputs(null, null, null, null, null);
    }
    public record Result(Code code, Map<Guard, State> states) {
        public Result { states = Map.copyOf(states); }
    }
    private record Snapshot(Code failure, long proofRevision, long processRevision,
                            CommerceRetentionHolds.Snapshot holds, State retryState) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommerceRetentionJobs jobs;
    private final CommerceRetentionHolds holds;

    public CommerceRetentionGlobalGuardEvidence(DataSource ds, PlatformTransactionManager manager) {
        this(ds, manager, null);
    }
    public CommerceRetentionGlobalGuardEvidence(DataSource ds, PlatformTransactionManager manager,
                                               JournalReceiptVerifier journalVerifier) {
        // Constructor compatibility: this verifier proves inclusion, not a current head.
        jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(2);
        tx = new TransactionTemplate(manager); tx.setTimeout(5);
        jobs = new CommerceRetentionJobs(ds, manager); holds = new CommerceRetentionHolds(ds, manager);
    }

    public Result collect(Subject subject, Phase phase, GuardInputs inputs) {
        Objects.requireNonNull(subject); Objects.requireNonNull(phase); Objects.requireNonNull(inputs);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("RETENTION_GUARD_CALLER_TRANSACTION");
        var before = tx.execute(status -> snapshot(subject, phase));
        if (before.failure() != null) return new Result(before.failure(), Map.of());

        // No SQL transaction or row/scope lock may span a remote/provider call.
        var evaluated = new EnumMap<Guard, State>(Guard.class);
        evaluated.put(Guard.PRESERVATION_POLICY_REVIEWED, State.UNKNOWN); // D01/P09 unresolved
        evaluated.put(Guard.INDEPENDENT_JOURNAL_CURRENT, State.UNKNOWN); // D09 custody/head unresolved
        evaluated.put(Guard.NO_PRESERVATION_HOLD, negative(() -> inputs.holdProvider() == null
                ? State.UNKNOWN : inputs.holdProvider().checkGlobalHold(subject.orderId(), subject.customerId(), phase)));
        evaluated.put(Guard.NO_OPEN_OR_REOPENED_CASE, negative(() -> inputs.caseProvider() == null
                ? State.UNKNOWN : inputs.caseProvider().checkCases(subject.orderId(), subject.customerId())));
        evaluated.put(Guard.NO_ACTIVE_RETRY_OR_DLQ, negative(() -> inputs.retryDlqProvider() == null
                ? State.UNKNOWN : inputs.retryDlqProvider().checkRetryDlq(subject.orderId(), subject.customerId())));

        return tx.execute(status -> {
            var after = snapshot(subject, phase);
            if (after.failure() != null) return new Result(after.failure(), Map.of());
            if (!before.equals(after)) return new Result(Code.STALE, Map.of());
            for (var guard : after.holds().blockers()) evaluated.put(guard, State.BLOCKED);
            if (after.retryState() == State.BLOCKED) evaluated.put(Guard.NO_ACTIVE_RETRY_OR_DLQ, State.BLOCKED);
            if (phase == Phase.MINIMUM_REMOVAL) {
                for (var guard : List.of(Guard.FINANCIAL_DEPENDENCIES_RELEASED, Guard.RESTORE_DEPENDENCIES_RELEASED,
                        Guard.RETRY_DEPENDENCIES_RELEASED, Guard.DEDUP_DEPENDENCIES_RELEASED))
                    evaluated.put(guard, State.BLOCKED);
            }
            for (var entry : evaluated.entrySet()) {
                var receipt = jobs.record(entry.getKey(), new GuardProof(subject, phase, POLICY, entry.getValue()));
                if (receipt == CommerceRetentionJobs.Receipt.CONFLICT || receipt == CommerceRetentionJobs.Receipt.STALE)
                    return new Result(Code.STALE, evaluated);
            }
            return new Result(evaluated.containsValue(State.BLOCKED) ? Code.BLOCKED : Code.WAITING_PROOF, evaluated);
        });
    }

    private Snapshot snapshot(Subject s, Phase phase) {
        var orders = jdbc.queryForList("SELECT customer_id,version,privacy_redacted FROM orders WHERE id=? FOR NO KEY UPDATE", s.orderId());
        if (orders.isEmpty() || customer(orders.getFirst()) != s.customerId() || n(orders.getFirst(), "version") != s.orderVersion())
            return failed(Code.STALE);
        var jobs = jdbc.queryForList("SELECT proof_revision FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND coalesce(customer_id,0)=? AND phase=? AND policy=? AND current FOR UPDATE",
                s.orderId(), s.chainGeneration(), s.orderVersion(), s.customerId(), phase.name(), POLICY);
        if (jobs.isEmpty()) return failed(Code.STALE);
        var h = holds.snapshot(s.orderId(), s.customerId(), phase);
        boolean fenced = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=?)
                OR EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=?)
                OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)
                OR EXISTS(SELECT 1 FROM commerce_retention_bindings WHERE order_id=? AND restore_blocked)
                """, Boolean.class, s.customerId(), s.orderId(), s.orderId(), s.orderId()));
        if (Boolean.TRUE.equals(orders.getFirst().get("privacy_redacted")) || h.quarantined() || fenced)
            return failed(Code.BLOCKED);
        var process = jdbc.queryForList("SELECT process_revision FROM commerce_retention_order_state WHERE order_id=?", s.orderId());
        // Real saga states and unfinished lease/retry markers; FAILED alone is not clearance.
        boolean pending = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM order_sagas WHERE order_id=? AND
                  (state NOT IN ('COMPLETED','FAILED') OR processing_token IS NOT NULL
                   OR processing_until IS NOT NULL OR next_attempt_at IS NOT NULL))
                """, Boolean.class, s.orderId()));
        return new Snapshot(null, n(jobs.getFirst(), "proof_revision"),
                process.isEmpty() ? -1 : n(process.getFirst(), "process_revision"), h,
                pending ? State.BLOCKED : State.UNKNOWN);
    }
    private static Snapshot failed(Code code) { return new Snapshot(code, -1, -1, null, State.UNKNOWN); }
    private static State negative(Supplier<State> provider) {
        try { return provider.get() == State.BLOCKED ? State.BLOCKED : State.UNKNOWN; }
        catch (RuntimeException unavailable) { return State.UNKNOWN; }
    }
    private static long n(Map<String, Object> r, String k) { return ((Number) r.get(k)).longValue(); }
    private static long customer(Map<String, Object> r) { return r.get("customer_id") == null ? 0 : n(r, "customer_id"); }
}
