package org.demo.platformsecurity.privacy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

class CommerceRetentionAssessmentTest {
    final Subject subject = new Subject(17, 23, 4, UUID.fromString("00000000-0000-0000-0000-000000000001"));
    final Instant closed = Instant.parse("2024-02-29T12:00:00Z");
    final Instant redacted = closed.plus(Duration.ofDays(500));

    ChainProof proof(Participant peer, Subject owner, Phase phase, State state, Instant end, Instant redaction) {
        return new ChainProof(peer, owner, phase, POLICY, state, end, redaction);
    }

    ArrayList<ChainProof> chain(Phase phase) {
        var proofs = new ArrayList<ChainProof>();
        for (var peer : Participant.values()) proofs.add(proof(peer, subject, phase, State.CONFIRMED,
                closed, phase == Phase.MINIMUM_REMOVAL ? redacted : null));
        return proofs;
    }

    EnumMap<Guard, GuardProof> guards(Phase phase) {
        var guards = new EnumMap<Guard, GuardProof>(Guard.class);
        for (var guard : requiredGuards(phase)) guards.put(guard, new GuardProof(subject, phase, POLICY, State.CONFIRMED));
        return guards;
    }

    Evidence evidence(Phase phase) { return new Evidence(subject, phase, chain(phase), guards(phase)); }
    Instant oldEnough() { return redacted.plus(Duration.ofDays(1000)); }

    @Test void redactionIsExactly365DaysAcrossLeapYearWithNoActivationPermission() {
        var due = Instant.parse("2025-02-28T12:00:00Z");
        assertThat(assess(evidence(Phase.REDACTION), due.minusNanos(1)).code()).isEqualTo(Code.AGE_NOT_REACHED);
        var result = assess(evidence(Phase.REDACTION), due);
        assertThat(result.outcome()).isEqualTo(Outcome.DUE_FOR_REVIEW);
        assertThat(result.eligibleAt()).isEqualTo(due);
        assertThat(result.activationAllowed()).isFalse();
    }

    @Test void latestReconciliationOfAllEightServicesStartsTheClock() {
        var chain = chain(Phase.REDACTION);
        var latest = closed.plus(Duration.ofDays(10));
        chain.set(1, proof(Participant.RETURNS, subject, Phase.REDACTION, State.CONFIRMED, latest, null));
        var evidence = new Evidence(subject, Phase.REDACTION, chain, guards(Phase.REDACTION));
        var result = assess(evidence, closed.plus(Duration.ofDays(365)));
        assertThat(result.outcome()).isEqualTo(Outcome.WAIT);
        assertThat(result.eligibleAt()).isEqualTo(latest.plus(Duration.ofDays(365)));
    }

    @Test void minimumIs90DaysAfterActualRedactionRatherThan455DaysAfterClosure() {
        var evidence = evidence(Phase.MINIMUM_REMOVAL);
        var early = assess(evidence, closed.plus(Duration.ofDays(455)));
        assertThat(early.outcome()).isNotEqualTo(Outcome.DUE_FOR_REVIEW);
        var eligibleAt = redacted.plus(Duration.ofDays(90));
        assertThat(assess(evidence, eligibleAt.minusNanos(1)).code()).isEqualTo(Code.AGE_NOT_REACHED);
        assertThat(assess(evidence, eligibleAt).outcome()).isEqualTo(Outcome.DUE_FOR_REVIEW);
        assertThat(assess(evidence, eligibleAt).activationAllowed()).isFalse();
    }

    @Test void finalRedactionReceiptStartsMinimumClockAndRestartDoesNotResetIt() {
        var chain = chain(Phase.MINIMUM_REMOVAL);
        var last = redacted.plusSeconds(10);
        chain.set(7, proof(Participant.NOTIFICATION, subject, Phase.MINIMUM_REMOVAL, State.CONFIRMED, closed, last));
        var evidence = new Evidence(subject, Phase.MINIMUM_REMOVAL, chain, guards(Phase.MINIMUM_REMOVAL));
        var due = last.plus(Duration.ofDays(90));
        assertThat(assess(evidence, due.minusNanos(1)).outcome()).isEqualTo(Outcome.WAIT);
        assertThat(assess(evidence, due).eligibleAt()).isEqualTo(due);
        assertThat(assess(evidence, due.plusSeconds(100)).eligibleAt()).isEqualTo(due);
    }

    @Test void missingOrDuplicateServiceIsNeverInterpretedAsNoLinkedRecords() {
        for (var phase : Phase.values()) for (var peer : Participant.values()) {
            var chain = chain(phase);
            chain.removeIf(p -> p.participant() == peer);
            assertThat(assess(new Evidence(subject, phase, chain, guards(phase)), oldEnough()).code())
                    .isEqualTo(Code.MISSING_CHAIN_PROOF);
            chain.add(chain.getFirst());
            assertThat(assess(new Evidence(subject, phase, chain, guards(phase)), oldEnough()).code())
                    .isEqualTo(Code.DUPLICATE_PARTICIPANT);
        }
    }

    @Test void wrongAccountOrderVersionOrChainGenerationCannotReuseOldProof() {
        var variants = List.of(new Subject(18, 23, 4, subject.chainGeneration()),
                new Subject(17, 24, 4, subject.chainGeneration()), new Subject(17, 23, 5, subject.chainGeneration()),
                new Subject(17, 23, 4, UUID.fromString("00000000-0000-0000-0000-000000000002")));
        for (var phase : Phase.values()) for (var changed : variants) {
            var chain = chain(phase);
            chain.set(0, proof(Participant.ORDER, changed, phase, State.CONFIRMED, closed, redacted));
            assertThat(assess(new Evidence(subject, phase, chain, guards(phase)), oldEnough()).code())
                    .isEqualTo(Code.CONFLICTING_PROOF);
            var guards = guards(phase);
            guards.put(Guard.FIELD_SCOPE_REVIEWED, new GuardProof(changed, phase, POLICY, State.CONFIRMED));
            assertThat(assess(new Evidence(subject, phase, chain(phase), guards), oldEnough()).code())
                    .isEqualTo(Code.CONFLICTING_PROOF);
        }
    }

    @Test void redactionProofAndOldPolicyCannotAuthorizeMinimumAssessment() {
        for (var phase : Phase.values()) for (var policy : List.of(POLICY, "old-policy")) {
            var chain = chain(phase);
            var otherPhase = policy.equals(POLICY) ? (phase == Phase.REDACTION ? Phase.MINIMUM_REMOVAL : Phase.REDACTION) : phase;
            chain.set(0, new ChainProof(Participant.ORDER, subject, otherPhase, policy, State.CONFIRMED, closed, redacted));
            assertThat(assess(new Evidence(subject, phase, chain, guards(phase)), oldEnough()).code())
                    .isEqualTo(Code.CONFLICTING_PROOF);
        }
    }

    @Test void everyUnreconciledOrUnknownParticipantBlocksBothPhases() {
        for (var phase : Phase.values()) for (var peer : Participant.values()) for (var state : List.of(State.BLOCKED, State.UNKNOWN)) {
            var chain = chain(phase);
            chain.set(peer.ordinal(), proof(peer, subject, phase, state, closed, redacted));
            var result = assess(new Evidence(subject, phase, chain, guards(phase)), oldEnough());
            assertThat(result.code()).isEqualTo(Code.UNRECONCILED_PROCESS);
            assertThat(result.outcome()).isNotEqualTo(Outcome.DUE_FOR_REVIEW);
        }
    }

    @Test void everyRequiredGuardNeedsExplicitPositiveProofAndHoldsWin() {
        for (var phase : Phase.values()) for (var guard : requiredGuards(phase)) {
            for (var state : List.of(State.BLOCKED, State.UNKNOWN)) {
                var guards = guards(phase);
                guards.put(guard, new GuardProof(subject, phase, POLICY, state));
                var result = assess(new Evidence(subject, phase, chain(phase), guards), oldEnough());
                assertThat(result.outcome()).isNotEqualTo(Outcome.DUE_FOR_REVIEW);
            }
            var guards = guards(phase);
            guards.remove(guard);
            assertThat(assess(new Evidence(subject, phase, chain(phase), guards), oldEnough()).code())
                    .isEqualTo(Code.MISSING_GUARD_PROOF);
        }
        var guards = guards(Phase.REDACTION);
        guards.put(Guard.FINANCIAL_DEPENDENCIES_RELEASED, new GuardProof(subject, Phase.REDACTION, POLICY, State.BLOCKED));
        assertThat(assess(new Evidence(subject, Phase.REDACTION, chain(Phase.REDACTION), guards), oldEnough()).outcome())
                .isEqualTo(Outcome.BLOCKED);
    }

    @Test void missingFutureOrContradictoryClocksNeverQualify() {
        var cases = List.of(
                proof(Participant.ORDER, subject, Phase.MINIMUM_REMOVAL, State.CONFIRMED, null, redacted),
                proof(Participant.ORDER, subject, Phase.MINIMUM_REMOVAL, State.CONFIRMED, closed, null),
                proof(Participant.ORDER, subject, Phase.MINIMUM_REMOVAL, State.CONFIRMED, closed, oldEnough().plusSeconds(1)),
                proof(Participant.ORDER, subject, Phase.MINIMUM_REMOVAL, State.CONFIRMED, oldEnough().plusSeconds(1), redacted),
                proof(Participant.ORDER, subject, Phase.MINIMUM_REMOVAL, State.CONFIRMED, closed, closed.minusNanos(1)));
        for (var invalid : cases) {
            var chain = chain(Phase.MINIMUM_REMOVAL); chain.set(0, invalid);
            assertThat(assess(new Evidence(subject, Phase.MINIMUM_REMOVAL, chain, guards(Phase.MINIMUM_REMOVAL)), oldEnough()).outcome())
                    .isNotEqualTo(Outcome.DUE_FOR_REVIEW);
        }
        var chain = chain(Phase.REDACTION);
        chain.set(0, proof(Participant.ORDER, subject, Phase.REDACTION, State.CONFIRMED, Instant.MAX, null));
        assertThat(assess(new Evidence(subject, Phase.REDACTION, chain, guards(Phase.REDACTION)), Instant.MAX).code())
                .isEqualTo(Code.CLOCK_RANGE);
    }

    @Test void evidenceIsBoundedImmutableAndContainsNoAccountDeletionRequirement() {
        var chain = chain(Phase.REDACTION); var guards = guards(Phase.REDACTION);
        var evidence = new Evidence(subject, Phase.REDACTION, chain, guards);
        chain.clear(); guards.clear();
        assertThat(assess(evidence, oldEnough()).outcome()).isEqualTo(Outcome.DUE_FOR_REVIEW);
        assertThatThrownBy(() -> evidence.chain().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.guards().clear()).isInstanceOf(UnsupportedOperationException.class);
        chain = chain(Phase.REDACTION); chain.add(chain.getFirst());
        var oversized = chain;
        assertThatThrownBy(() -> new Evidence(subject, Phase.REDACTION, oversized, guards(Phase.REDACTION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Subject(0, 23, 4, subject.chainGeneration())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void guestPurchasesNeedNoAccountButCannotShareProofsThroughTheAbsentCustomerId() {
        var guest = new Subject(71, 0, 4, subject.chainGeneration());
        var otherGuest = new Subject(72, 0, 4, subject.chainGeneration());
        var chain = new ArrayList<ChainProof>();
        for (var peer : Participant.values()) chain.add(proof(peer, guest, Phase.REDACTION, State.CONFIRMED, closed, null));
        var guards = new EnumMap<Guard, GuardProof>(Guard.class);
        for (var guard : requiredGuards(Phase.REDACTION)) guards.put(guard, new GuardProof(guest, Phase.REDACTION, POLICY, State.CONFIRMED));
        assertThat(assess(new Evidence(guest, Phase.REDACTION, chain, guards), oldEnough()).outcome()).isEqualTo(Outcome.DUE_FOR_REVIEW);
        chain.set(0, proof(Participant.ORDER, otherGuest, Phase.REDACTION, State.CONFIRMED, closed, null));
        assertThat(assess(new Evidence(guest, Phase.REDACTION, chain, guards), oldEnough()).code()).isEqualTo(Code.CONFLICTING_PROOF);
        assertThatThrownBy(() -> new Subject(71, -1, 4, subject.chainGeneration())).isInstanceOf(IllegalArgumentException.class);
    }
}
