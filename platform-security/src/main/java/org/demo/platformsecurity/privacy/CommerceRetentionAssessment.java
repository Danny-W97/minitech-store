package org.demo.platformsecurity.privacy;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only D02/P02 assessment, independent of the account-erasure protocol.
 * Inputs must be read from trusted durable evidence under the current chain
 * generation, not accepted as client assertions. This class neither obtains
 * that evidence nor authorizes/writes a cleanup. A future executor must recheck
 * evidence and all dependencies under its write locks before changing data.
 */
public final class CommerceRetentionAssessment {
    public static final String POLICY = "D02-P02-2026-10-30.1";
    public static final Duration REDACTION_AGE = Duration.ofDays(365);
    public static final Duration MINIMUM_AGE = Duration.ofDays(90);

    public enum Phase { REDACTION, MINIMUM_REMOVAL }
    public enum Participant { ORDER, RETURNS, PAYMENT, INVENTORY, PROMOTION, DELIVERY, BILLING, NOTIFICATION }
    public enum State { CONFIRMED, BLOCKED, UNKNOWN }
    public enum Guard {
        FIELD_SCOPE_REVIEWED, PRESERVATION_POLICY_REVIEWED, NO_PRESERVATION_HOLD,
        NO_OPEN_OR_REOPENED_CASE, NO_ACTIVE_RETRY_OR_DLQ, INDEPENDENT_JOURNAL_CURRENT,
        FINANCIAL_DEPENDENCIES_RELEASED, RESTORE_DEPENDENCIES_RELEASED,
        RETRY_DEPENDENCIES_RELEASED, DEDUP_DEPENDENCIES_RELEASED
    }
    public enum Outcome { DUE_FOR_REVIEW, WAIT, BLOCKED, REVIEW_REQUIRED }
    public enum Code {
        AGE_REACHED, AGE_NOT_REACHED, MISSING_CHAIN_PROOF, DUPLICATE_PARTICIPANT,
        CONFLICTING_PROOF, UNRECONCILED_PROCESS, MISSING_CLOCK, FUTURE_CLOCK,
        MISSING_GUARD_PROOF, PRESERVATION_OR_DEPENDENCY_BLOCK,
        MISSING_REDACTION_PROOF, REDACTION_BEFORE_RECONCILIATION, CLOCK_RANGE
    }

    /**
     * Generation must change whenever any linked process reopens or changes.
     * customerId=0 denotes an order with no account, never a shared guest identity;
     * orderId, version and generation still bind every proof to one purchase.
     */
    public record Subject(long orderId, long customerId, long orderVersion, UUID chainGeneration) {
        public Subject {
            if (orderId < 1 || customerId < 0 || orderVersion < 0 || chainGeneration == null) {
                throw new IllegalArgumentException("Invalid retention subject");
            }
        }
    }

    /** An explicit confirmed absence of records still requires a service proof. */
    public record ChainProof(Participant participant, Subject subject, Phase phase, String policy,
                             State state, Instant reconciledAt, Instant redactedAt) {
        public ChainProof {
            Objects.requireNonNull(participant); Objects.requireNonNull(subject);
            Objects.requireNonNull(phase); Objects.requireNonNull(state);
        }
    }

    public record GuardProof(Subject subject, Phase phase, String policy, State state) {
        public GuardProof {
            Objects.requireNonNull(subject); Objects.requireNonNull(phase); Objects.requireNonNull(state);
        }
    }

    public record Evidence(Subject subject, Phase phase, List<ChainProof> chain,
                           Map<Guard, GuardProof> guards) {
        public Evidence {
            Objects.requireNonNull(subject); Objects.requireNonNull(phase);
            chain = List.copyOf(chain);
            guards = Map.copyOf(guards);
            if (chain.size() > Participant.values().length) {
                throw new IllegalArgumentException("Too many retention proofs");
            }
        }
    }

    /** A due result is an assessment only, never a deletion permission. */
    public record Result(Outcome outcome, Code code, Instant eligibleAt) {
        public boolean activationAllowed() { return false; }
    }

    private CommerceRetentionAssessment() {}

    public static Set<Guard> requiredGuards(Phase phase) {
        Objects.requireNonNull(phase);
        var guards = EnumSet.of(Guard.FIELD_SCOPE_REVIEWED, Guard.PRESERVATION_POLICY_REVIEWED,
                Guard.NO_PRESERVATION_HOLD, Guard.NO_OPEN_OR_REOPENED_CASE,
                Guard.NO_ACTIVE_RETRY_OR_DLQ, Guard.INDEPENDENT_JOURNAL_CURRENT);
        if (phase == Phase.MINIMUM_REMOVAL) {
            guards.addAll(EnumSet.of(Guard.FINANCIAL_DEPENDENCIES_RELEASED,
                    Guard.RESTORE_DEPENDENCIES_RELEASED, Guard.RETRY_DEPENDENCIES_RELEASED,
                    Guard.DEDUP_DEPENDENCIES_RELEASED));
        }
        return Set.copyOf(guards);
    }

    public static Result assess(Evidence evidence, Instant now) {
        Objects.requireNonNull(evidence); Objects.requireNonNull(now);
        var peers = new EnumMap<Participant, ChainProof>(Participant.class);
        for (var proof : evidence.chain()) {
            if (peers.putIfAbsent(proof.participant(), proof) != null) {
                return result(Outcome.REVIEW_REQUIRED, Code.DUPLICATE_PARTICIPANT);
            }
            if (!matches(evidence, proof.subject(), proof.phase(), proof.policy())) {
                return result(Outcome.REVIEW_REQUIRED, Code.CONFLICTING_PROOF);
            }
        }
        if (peers.size() != Participant.values().length) {
            return result(Outcome.WAIT, Code.MISSING_CHAIN_PROOF);
        }
        Instant reconciledAt = Instant.MIN;
        for (var participant : Participant.values()) {
            var proof = peers.get(participant);
            if (proof.state() != State.CONFIRMED) {
                return result(proof.state() == State.BLOCKED ? Outcome.BLOCKED : Outcome.WAIT,
                        Code.UNRECONCILED_PROCESS);
            }
            if (proof.reconciledAt() == null) return result(Outcome.WAIT, Code.MISSING_CLOCK);
            if (proof.reconciledAt().isAfter(now)) return result(Outcome.REVIEW_REQUIRED, Code.FUTURE_CLOCK);
            if (proof.reconciledAt().isAfter(reconciledAt)) reconciledAt = proof.reconciledAt();
        }
        // Validate every supplied guard, including optional guards, before using
        // the required set. Contradictory or stale additional evidence cannot pass.
        for (var proof : evidence.guards().values()) {
            if (!matches(evidence, proof.subject(), proof.phase(), proof.policy())) {
                return result(Outcome.REVIEW_REQUIRED, Code.CONFLICTING_PROOF);
            }
            if (proof.state() == State.BLOCKED) {
                return result(Outcome.BLOCKED, Code.PRESERVATION_OR_DEPENDENCY_BLOCK);
            }
        }
        for (var guard : requiredGuards(evidence.phase())) {
            var proof = evidence.guards().get(guard);
            if (proof == null || proof.state() != State.CONFIRMED) {
                return result(Outcome.WAIT, Code.MISSING_GUARD_PROOF);
            }
        }
        Instant startsAt = reconciledAt;
        if (evidence.phase() == Phase.MINIMUM_REMOVAL) {
            startsAt = Instant.MIN;
            for (var proof : peers.values()) {
                if (proof.redactedAt() == null) return result(Outcome.WAIT, Code.MISSING_REDACTION_PROOF);
                if (proof.redactedAt().isAfter(now)) return result(Outcome.REVIEW_REQUIRED, Code.FUTURE_CLOCK);
                if (proof.redactedAt().isBefore(reconciledAt)) {
                    return result(Outcome.REVIEW_REQUIRED, Code.REDACTION_BEFORE_RECONCILIATION);
                }
                if (proof.redactedAt().isAfter(startsAt)) startsAt = proof.redactedAt();
            }
        }
        try {
            Instant eligibleAt = startsAt.plus(evidence.phase() == Phase.REDACTION ? REDACTION_AGE : MINIMUM_AGE);
            return new Result(now.isBefore(eligibleAt) ? Outcome.WAIT : Outcome.DUE_FOR_REVIEW,
                    now.isBefore(eligibleAt) ? Code.AGE_NOT_REACHED : Code.AGE_REACHED, eligibleAt);
        } catch (DateTimeException | ArithmeticException ex) {
            return result(Outcome.REVIEW_REQUIRED, Code.CLOCK_RANGE);
        }
    }

    private static boolean matches(Evidence evidence, Subject subject, Phase phase, String policy) {
        return evidence.subject().equals(subject) && evidence.phase() == phase && POLICY.equals(policy);
    }

    private static Result result(Outcome outcome, Code code) { return new Result(outcome, code, null); }
}
