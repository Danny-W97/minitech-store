package org.demo.platformsecurity.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements D15 and D18 manual case procedures, role assignments, and worker stop controls:
 * <ul>
 *   <li>Owner role: {@code operator-1}; Deputy role: {@code operator-2}.</li>
 *   <li>Additional review for ambiguous cases (P08/D15) strictly restricted to owner {@code operator-1}.</li>
 *   <li>Retention: case evidence retained 90 days from confirmed safe closure; minimal record retained 365 days.</li>
 *   <li>Active/disputed cases permanently block data/case cleanup under P09.</li>
 *   <li>Scoped worker stop procedure: enables authorized immediate stoppage of a specific worker with audit receipt.</li>
 * </ul>
 */
public final class ManualCaseProcedureService {

    public static final String ROLE_OWNER = "operator-1";
    public static final String ROLE_DEPUTY = "operator-2";
    public static final Set<String> AUTHORIZED_OPERATORS = Set.of(ROLE_OWNER, ROLE_DEPUTY);

    public static final Duration EVIDENCE_RETENTION = Duration.ofDays(90);
    public static final Duration MINIMAL_RECORD_RETENTION = Duration.ofDays(365);

    public enum CaseStatus {
        OPEN,
        UNDER_AMBIGUOUS_REVIEW,
        CLOSED_CONFIRMED,
        DISPUTED
    }

    public enum CaseDecision {
        PENDING,
        APPROVED,
        REJECTED,
        UNRESOLVED
    }

    public record CaseRecord(
            String caseId,
            String subjectReference,
            CaseStatus status,
            CaseDecision decision,
            String decidedBy,
            Instant createdAt,
            Instant closedAt,
            boolean hasEvidenceFiles,
            boolean evidencePruned,
            boolean minimalRecordPruned
    ) {
        public boolean isClosed() {
            return status == CaseStatus.CLOSED_CONFIRMED && closedAt != null;
        }

        public boolean isActiveOrDisputed() {
            return status == CaseStatus.OPEN || status == CaseStatus.UNDER_AMBIGUOUS_REVIEW || status == CaseStatus.DISPUTED;
        }
    }

    public record WorkerStopReceipt(
            String workerId,
            String stoppedByOperator,
            String reason,
            Instant stoppedAt,
            boolean executionTerminated
    ) {}

    public record RetentionEligibility(boolean evidenceEligible, boolean minimumEligible) {}

    private final Map<String, CaseRecord> cases = new ConcurrentHashMap<>();
    private final Map<String, WorkerStopReceipt> stoppedWorkers = new ConcurrentHashMap<>();
    private final OperationalRetentionGuard guard;

    public ManualCaseProcedureService(OperationalRetentionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
    }

    public CaseRecord createCase(String caseId, String subjectReference, Instant now) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(subjectReference, "subjectReference must not be null");
        Objects.requireNonNull(now, "now must not be null");

        CaseRecord record = new CaseRecord(caseId, subjectReference, CaseStatus.OPEN, CaseDecision.PENDING,
                null, now, null, true, false, false);
        if (cases.putIfAbsent(caseId, record) != null) throw new IllegalStateException("CASE_ALREADY_EXISTS");
        // Register hold in operational guard
        guard.registerHold("case-" + caseId, OperationalRetentionGuard.Scope.CASES, subjectReference,
                "Active manual case " + caseId, ROLE_OWNER, now);
        return record;
    }

    public CaseRecord markAmbiguous(String caseId) {
        CaseRecord record = getCaseOrThrow(caseId);
        CaseRecord updated = new CaseRecord(record.caseId(), record.subjectReference(), CaseStatus.UNDER_AMBIGUOUS_REVIEW,
                CaseDecision.PENDING, record.decidedBy(), record.createdAt(), record.closedAt(),
                record.hasEvidenceFiles(), record.evidencePruned(), record.minimalRecordPruned());
        cases.put(caseId, updated);
        return updated;
    }

    /**
     * Resolves an ambiguous case evaluation.
     * <p>
     * Under D15/P08, only the owner (operator-1) is authorized to resolve ambiguous cases.
     * If attempted by deputy (operator-2) or automated process, throws SecurityException and leaves case UNRESOLVED.
     */
    public CaseRecord resolveAmbiguousCase(String caseId, String operator, CaseDecision decision, Instant now) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(now, "now must not be null");

        CaseRecord record = getCaseOrThrow(caseId);

        if (!ROLE_OWNER.equals(operator)) {
            // P08 strict requirement: owner-only-operator-1-no-second-person-requirement
            throw new SecurityException("Ambiguous case review requires primary owner (" + ROLE_OWNER + "); operator " + operator + " is not authorized.");
        }

        CaseStatus nextStatus = (decision == CaseDecision.APPROVED || decision == CaseDecision.REJECTED)
                ? CaseStatus.CLOSED_CONFIRMED
                : CaseStatus.UNDER_AMBIGUOUS_REVIEW;
        Instant closedAt = nextStatus == CaseStatus.CLOSED_CONFIRMED ? now : null;

        CaseRecord updated = new CaseRecord(record.caseId(), record.subjectReference(), nextStatus,
                decision, operator, record.createdAt(), closedAt, record.hasEvidenceFiles(), record.evidencePruned(), record.minimalRecordPruned());
        cases.put(caseId, updated);

        if (nextStatus == CaseStatus.CLOSED_CONFIRMED) {
            guard.releaseHold("case-" + caseId, operator, now);
        }
        return updated;
    }

    /** Read-only qualification; no storage adapter exists to attest deletion. */
    public RetentionEligibility retentionEligibility(String caseId, Instant clock) {
        Objects.requireNonNull(clock);
        CaseRecord record = getCaseOrThrow(caseId);
        synchronized (guard) {
            if (!record.isClosed() || record.isActiveOrDisputed()
                    || guard.isCleanupBlocked(OperationalRetentionGuard.Scope.CASES, record.subjectReference())
                    || guard.isCleanupBlocked(OperationalRetentionGuard.Scope.CASES, caseId)) {
                return new RetentionEligibility(false, false);
            }
            Duration age = Duration.between(record.closedAt(), clock);
            return new RetentionEligibility(age.compareTo(EVIDENCE_RETENTION) >= 0,
                    age.compareTo(MINIMAL_RECORD_RETENTION) >= 0);
        }
    }

    /** Compatibility read: qualification never claims completed deletion. */
    public CaseRecord evaluateCaseRetention(String caseId, Instant clock) {
        retentionEligibility(caseId, clock);
        return getCaseOrThrow(caseId);
    }

    /**
     * Executes D18 scoped worker stop procedure.
     * Authorized for both owner (operator-1) and deputy (operator-2).
     */
    public WorkerStopReceipt executeScopedWorkerStop(String workerId, String operator, String reason, Instant now) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");

        if (!AUTHORIZED_OPERATORS.contains(operator)) {
            throw new SecurityException("Operator " + operator + " is not authorized to execute scoped worker stop.");
        }

        // This is a stop request in the local model, not an execution acknowledgement.
        WorkerStopReceipt receipt = new WorkerStopReceipt(workerId, operator, reason, now, false);
        stoppedWorkers.put(workerId, receipt);
        return receipt;
    }

    public boolean isWorkerStopped(String workerId) {
        var receipt = stoppedWorkers.get(workerId);
        return receipt != null && receipt.executionTerminated();
    }

    public boolean isStopRequested(String workerId) {
        return stoppedWorkers.containsKey(workerId);
    }

    public Map<String, WorkerStopReceipt> getStoppedWorkers() {
        return Collections.unmodifiableMap(stoppedWorkers);
    }

    public Map<String, CaseRecord> getAllCases() {
        return Collections.unmodifiableMap(cases);
    }

    private CaseRecord getCaseOrThrow(String caseId) {
        CaseRecord record = cases.get(caseId);
        if (record == null) {
            throw new IllegalArgumentException("Case " + caseId + " not found");
        }
        return record;
    }
}
