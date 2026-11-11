package org.demo.platformsecurity.privacy;

import java.time.Instant;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operational retention preservation guard implementing fail-closed P09 exception controls
 * for logs (D07), monitoring (D08), mailpit developer email (D14), and manual case records (D15).
 * <p>
 * Under P09=C (unresolved preservation exceptions), records subject to active holds,
 * disputes, or missing clearance cannot be deleted by automated age-based or size-based retention.
 */
public final class OperationalRetentionGuard {

    public enum Scope {
        LOGS,
        MONITORING,
        MAILPIT,
        CASES
    }

    public enum HoldStatus {
        ACTIVE,
        RELEASED
    }

    public record PreservationHold(
            String holdId,
            Scope scope,
            String targetReference,
            String reason,
            String createdBy,
            Instant createdAt,
            HoldStatus status,
            Instant releasedAt,
            String releasedBy
    ) {
        public PreservationHold {
            Objects.requireNonNull(holdId, "holdId must not be null");
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(targetReference, "targetReference must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(createdBy, "createdBy must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }

        public boolean isActive() {
            return status == HoldStatus.ACTIVE;
        }

        public PreservationHold release(String releasedBy, Instant releasedAt) {
            if (this.status == HoldStatus.RELEASED) {
                return this;
            }
            return new PreservationHold(
                    this.holdId,
                    this.scope,
                    this.targetReference,
                    this.reason,
                    this.createdBy,
                    this.createdAt,
                    HoldStatus.RELEASED,
                    Objects.requireNonNull(releasedAt, "releasedAt must not be null"),
                    Objects.requireNonNull(releasedBy, "releasedBy must not be null")
            );
        }
    }

    private final Map<String, PreservationHold> holds = new ConcurrentHashMap<>();
    public static final String POLICY_VERSION = "operational-retention-2026-11-01.1";

    /** Supplied only by trusted application code; no runtime provider is configured yet. */
    @FunctionalInterface
    interface ClearanceProvider {
        Optional<Clearance> find(Scope scope, String target);
    }

    record Clearance(Scope scope, String target, String policy, String evidenceId,
                     Instant validFrom, Instant validUntil) {}

    private final ClearanceProvider clearances;
    private final Clock clock;

    /** A boolean is not a reviewed P09 clearance. Kept for source compatibility. */
    @Deprecated
    public OperationalRetentionGuard(boolean defaultFailClosed) {
        this();
    }

    public OperationalRetentionGuard() {
        this((scope, target) -> Optional.empty(), Clock.systemUTC());
    }

    // Package-local injection for policy model tests; production construction stays closed.
    OperationalRetentionGuard(ClearanceProvider clearances, Clock clock) {
        this.clearances = Objects.requireNonNull(clearances);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void registerHold(String holdId, Scope scope, String targetReference, String reason, String operator, Instant now) {
        var hold = new PreservationHold(holdId, scope, targetReference, reason, operator, now, HoldStatus.ACTIVE, null, null);
        if (holds.putIfAbsent(holdId, hold) != null) throw new IllegalStateException("HOLD_ALREADY_EXISTS");
    }

    public synchronized boolean releaseHold(String holdId, String operator, Instant now) {
        PreservationHold existing = holds.get(holdId);
        if (existing == null || !existing.isActive()) {
            return false;
        }
        holds.put(holdId, existing.release(operator, now));
        return true;
    }

    /**
     * Checks whether an operational target is blocked from retention cleanup under P09.
     *
     * @param scope           operational scope (LOGS, MONITORING, MAILPIT, CASES)
     * @param targetReference identifier or path of the target resource
     * @return true if cleanup is blocked (hold present or unresolved exception); false only if explicitly cleared
     */
    public synchronized boolean isCleanupBlocked(Scope scope, String targetReference) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(targetReference, "targetReference must not be null");

        for (PreservationHold hold : holds.values()) {
            if (hold.isActive() && hold.scope() == scope && matches(hold.targetReference(), targetReference)) {
                return true;
            }
        }
        try {
            var proof = clearances.find(scope, targetReference).orElse(null);
            Instant now = clock.instant();
            return proof == null || proof.scope() != scope || !targetReference.equals(proof.target())
                    || !POLICY_VERSION.equals(proof.policy()) || proof.evidenceId() == null || proof.evidenceId().isBlank()
                    || proof.validFrom() == null || proof.validUntil() == null
                    || now.isBefore(proof.validFrom()) || !now.isBefore(proof.validUntil());
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    public boolean isDefaultFailClosed() {
        return true;
    }

    public Map<String, PreservationHold> getActiveHolds() {
        Map<String, PreservationHold> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, PreservationHold> entry : holds.entrySet()) {
            if (entry.getValue().isActive()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private boolean matches(String pattern, String candidate) {
        if (pattern.equals("*") || pattern.equalsIgnoreCase(candidate)) {
            return true;
        }
        if (pattern.endsWith("*") && candidate.startsWith(pattern.substring(0, pattern.length() - 1))) {
            return true;
        }
        return candidate.contains(pattern);
    }
}
