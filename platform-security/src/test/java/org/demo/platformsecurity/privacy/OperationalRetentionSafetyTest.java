package org.demo.platformsecurity.privacy;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OperationalRetentionSafetyTest {
    final Instant now = Instant.parse("2026-11-01T12:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    OperationalRetentionGuard.Clearance proof(OperationalRetentionGuard.Scope scope, String target) {
        return new OperationalRetentionGuard.Clearance(scope,target,OperationalRetentionGuard.POLICY_VERSION,
                "fixture",now.minusSeconds(1),now.plusSeconds(60));
    }
    OperationalRetentionGuard cleared() { return new OperationalRetentionGuard((s,t)->Optional.of(proof(s,t)),clock); }

    @Test void noClearanceBlocksEveryPolicyModelAndCannotEnableFileDeletion() {
        var guard=new OperationalRetentionGuard();
        var file=new OperationalLogRetention.LogFileMetadata(Path.of("fixture.log"),"fixture",200L*1024*1024,now.minus(Duration.ofDays(40)),true,false,false);
        var logs=new OperationalLogRetention(guard);
        assertThat(logs.processSource("fixture",List.of(file),now,true).filesPruned()).isZero();
        assertThatThrownBy(()->logs.processSource("fixture",List.of(file),now,false)).isInstanceOf(IllegalStateException.class);
        var message=new MailpitRetentionManager.MailpitMessage("id","header","fixture","fixture","fixture",now.minus(Duration.ofDays(40)),1,1,false);
        assertThat(new MailpitRetentionManager(guard).evaluateAndPrune(List.of(message),now).prunedCount()).isZero();
        var dashboard=new MonitoringRetentionPolicy.RetiredDashboard("id","fixture",now.minus(Duration.ofDays(100)),false,true);
        assertThat(new MonitoringRetentionPolicy(guard).reviewRetiredDashboards(List.of(dashboard),now).getFirst().action())
                .isEqualTo(MonitoringRetentionPolicy.DashboardAction.BLOCKED_BY_HOLD);
    }

    @Test void unavailableExpiredWrongScopeOrUnreviewedPolicyNeverClearsP09() {
        var scope=OperationalRetentionGuard.Scope.LOGS;
        var invalid=List.of(
                new OperationalRetentionGuard.Clearance(scope,"other",OperationalRetentionGuard.POLICY_VERSION,"id",now.minusSeconds(1),now.plusSeconds(1)),
                new OperationalRetentionGuard.Clearance(OperationalRetentionGuard.Scope.CASES,"id",OperationalRetentionGuard.POLICY_VERSION,"id",now.minusSeconds(1),now.plusSeconds(1)),
                new OperationalRetentionGuard.Clearance(scope,"id","old","id",now.minusSeconds(1),now.plusSeconds(1)),
                new OperationalRetentionGuard.Clearance(scope,"id",OperationalRetentionGuard.POLICY_VERSION,"id",now.minusSeconds(2),now),
                new OperationalRetentionGuard.Clearance(scope,"id",OperationalRetentionGuard.POLICY_VERSION,"id",now.plusSeconds(1),now.plusSeconds(2)));
        for(var p:invalid) assertThat(new OperationalRetentionGuard((s,t)->Optional.of(p),clock).isCleanupBlocked(scope,"id")).isTrue();
        assertThat(new OperationalRetentionGuard((s,t)->{throw new IllegalStateException();},clock).isCleanupBlocked(scope,"id")).isTrue();
    }

    @Test void sourceHoldProtectsRotatedSegmentWhenSizeLimitIsExceeded() {
        var guard=cleared();
        guard.registerHold("h",OperationalRetentionGuard.Scope.LOGS,"source","fixture","operator-1",now);
        var file=new OperationalLogRetention.LogFileMetadata(Path.of("segment.log"),"source",150L*1024*1024,now,true,false,false);
        var result=new OperationalLogRetention(guard).processSource("source",List.of(file),now,true);
        assertThat(result.filesBlocked()).isOne(); assertThat(result.filesPruned()).isZero();
        assertThat(result.finalSizeBytes()).isEqualTo(file.sizeBytes());
    }

    @Test void newHoldAfterClosureBlocksBothCaseRetentionPhases() {
        var guard=cleared(); var cases=new ManualCaseProcedureService(guard);
        cases.createCase("case","subject",now.minus(Duration.ofDays(500)));
        cases.resolveAmbiguousCase("case","operator-1",ManualCaseProcedureService.CaseDecision.APPROVED,now.minus(Duration.ofDays(400)));
        guard.registerHold("new",OperationalRetentionGuard.Scope.CASES,"subject","fixture","operator-1",now);
        assertThat(cases.retentionEligibility("case",now)).isEqualTo(new ManualCaseProcedureService.RetentionEligibility(false,false));
        assertThat(cases.evaluateCaseRetention("case",now).evidencePruned()).isFalse();
        assertThat(cases.evaluateCaseRetention("case",now).minimalRecordPruned()).isFalse();
    }

    @Test void holdReleaseAloneDoesNotConstituteClearance() {
        var guard=new OperationalRetentionGuard();
        guard.registerHold("h",OperationalRetentionGuard.Scope.CASES,"subject","fixture","operator-1",now);
        assertThat(guard.releaseHold("h","operator-1",now)).isTrue();
        assertThat(guard.isCleanupBlocked(OperationalRetentionGuard.Scope.CASES,"subject")).isTrue();
    }
}
