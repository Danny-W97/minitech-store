package org.demo.platformsecurity.privacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalRetentionAndManualProceduresTest {

    private OperationalRetentionGuard guard;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-01T12:00:00Z");
        guard = new OperationalRetentionGuard((scope, target) -> Optional.of(
                new OperationalRetentionGuard.Clearance(scope, target, OperationalRetentionGuard.POLICY_VERSION,
                        "isolated-fixture", now.minusSeconds(1), now.plus(Duration.ofDays(1000)))), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("P09 Fail-Closed Guard Tests")
    class GuardTests {

        @Test
        void defaultGuardIsFailClosed() {
            var closed = new OperationalRetentionGuard();
            assertThat(closed.isDefaultFailClosed()).isTrue();
            for (var scope : OperationalRetentionGuard.Scope.values())
                assertThat(closed.isCleanupBlocked(scope, "fixture")).isTrue();
            assertThat(new OperationalRetentionGuard(false).isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, "fixture")).isTrue();
        }

        @Test
        void registeredHoldBlocksCleanupUntilExplicitlyReleased() {
            guard.registerHold("HOLD-1", OperationalRetentionGuard.Scope.LOGS, "/var/log/minitech/order-service.log",
                    "Court dispute 2026/09", ManualCaseProcedureService.ROLE_OWNER, now);

            assertThat(guard.isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, "/var/log/minitech/order-service.log")).isTrue();
            assertThat(guard.isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, "/var/log/minitech/customer-service.log")).isFalse();

            boolean released = guard.releaseHold("HOLD-1", ManualCaseProcedureService.ROLE_OWNER, now.plus(Duration.ofDays(10)));
            assertThat(released).isTrue();
            assertThat(guard.isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, "/var/log/minitech/order-service.log")).isFalse();
        }

        @Test
        void wildcardPatternMatchesMatchingTargets() {
            guard.registerHold("HOLD-ALL-CASES", OperationalRetentionGuard.Scope.CASES, "case-disputed-*",
                    "All disputed cases hold", ManualCaseProcedureService.ROLE_OWNER, now);

            assertThat(guard.isCleanupBlocked(OperationalRetentionGuard.Scope.CASES, "case-disputed-101")).isTrue();
            assertThat(guard.isCleanupBlocked(OperationalRetentionGuard.Scope.CASES, "case-normal-202")).isFalse();
        }
    }

    @Nested
    @DisplayName("D07 Operational Log Retention Tests")
    class LogRetentionTests {

        private OperationalLogRetention logRetention;

        @BeforeEach
        void init() {
            logRetention = new OperationalLogRetention(guard);
        }

        @Test
        void prunesLogsOlderThan30DaysFromEntryWrite() {
            List<OperationalLogRetention.LogFileMetadata> files = List.of(
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/order.log"), "order-service", 10_000, now.minus(Duration.ofDays(5)), false, false, false),
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/order.1.log"), "order-service", 20_000, now.minus(Duration.ofDays(31)), true, false, false)
            );

            OperationalLogRetention.ExecutionReport report = logRetention.processSource("order-service", files, now, true);

            assertThat(report.filesPruned()).isEqualTo(1);
            assertThat(report.decisions()).anyMatch(d -> d.action() == OperationalLogRetention.RetentionAction.PRUNED_BY_AGE
                    && d.path().toString().contains("order.1.log"));
        }

        @Test
        void prunesOldestRotatedSegmentsWhenTotalSizeExceeds100MiB() {
            long segmentSize = 40 * 1024 * 1024; // 40 MiB
            List<OperationalLogRetention.LogFileMetadata> files = List.of(
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/active.log"), "service-a", segmentSize, now.minus(Duration.ofDays(1)), false, false, false),
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/rot-1.log"), "service-a", segmentSize, now.minus(Duration.ofDays(2)), true, false, false),
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/rot-2.log"), "service-a", segmentSize, now.minus(Duration.ofDays(3)), true, false, false)
            ); // Total = 120 MiB > 100 MiB limit

            OperationalLogRetention.ExecutionReport report = logRetention.processSource("service-a", files, now, true);

            assertThat(report.filesPruned()).isEqualTo(1);
            assertThat(report.finalSizeBytes()).isLessThanOrEqualTo(OperationalLogRetention.MAX_BYTES_PER_SOURCE);
            assertThat(report.decisions()).anyMatch(d -> d.action() == OperationalLogRetention.RetentionAction.PRUNED_BY_SIZE
                    && d.path().toString().contains("rot-2.log")); // oldest pruned first
        }

        @Test
        void protectsD10LegacyAndCertifiedJobLogsAndHeldFiles() {
            guard.registerHold("HOLD-ORDER", OperationalRetentionGuard.Scope.LOGS, "/logs/held.log",
                    "Audit hold", ManualCaseProcedureService.ROLE_OWNER, now);

            List<OperationalLogRetention.LogFileMetadata> files = List.of(
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/legacy-d10.txt"), "legacy", 10_000, now.minus(Duration.ofDays(500)), false, true, false),
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/job-123.log"), "cc-job", 10_000, now.minus(Duration.ofDays(20)), false, false, true),
                    new OperationalLogRetention.LogFileMetadata(Path.of("/logs/held.log"), "held-src", 10_000, now.minus(Duration.ofDays(45)), false, false, false)
            );

            OperationalLogRetention.ExecutionReport report = logRetention.processSource("multi", files, now, true);

            assertThat(report.filesPruned()).isZero();
            assertThat(report.filesBlocked()).isEqualTo(1);
            assertThat(report.decisions()).anyMatch(d -> d.action() == OperationalLogRetention.RetentionAction.PROTECTED_LEGACY);
            assertThat(report.decisions()).anyMatch(d -> d.action() == OperationalLogRetention.RetentionAction.PROTECTED_CERTIFIED);
            assertThat(report.decisions()).anyMatch(d -> d.action() == OperationalLogRetention.RetentionAction.BLOCKED_BY_HOLD);
        }
    }

    @Nested
    @DisplayName("D08 Monitoring Retention Policy Tests")
    class MonitoringPolicyTests {

        private MonitoringRetentionPolicy policy;

        @BeforeEach
        void init() {
            policy = new MonitoringRetentionPolicy(guard);
        }

        @Test
        void verifiesPrometheusAndAlertmanagerParameters() {
            var promOk = policy.verifyPrometheus("--storage.tsdb.retention.time=30d", 500_000_000L);
            assertThat(promOk.isTimeRetentionCompliant()).isTrue();
            assertThat(promOk.alarmTriggered()).isFalse();

            var promAlarm = policy.verifyPrometheus("--storage.tsdb.retention.time=30d", 1_100_000_000L);
            assertThat(promAlarm.alarmTriggered()).isTrue();

            var alertOk = policy.verifyAlertmanager("--data.retention=120h");
            assertThat(alertOk.isRetentionCompliant()).isTrue();

            var alertBad = policy.verifyAlertmanager("--data.retention=24h");
            assertThat(alertBad.isRetentionCompliant()).isFalse();
        }

        @Test
        void evaluatesRetiredGrafanaDashboardsRetention() {
            guard.registerHold("HOLD-DASH", OperationalRetentionGuard.Scope.MONITORING, "dash-3",
                    "Dispute on metric integrity", ManualCaseProcedureService.ROLE_OWNER, now);

            List<MonitoringRetentionPolicy.RetiredDashboard> dashboards = List.of(
                    new MonitoringRetentionPolicy.RetiredDashboard("dash-1", "Old Orders", now.minus(Duration.ofDays(95)), false, true),
                    new MonitoringRetentionPolicy.RetiredDashboard("dash-2", "Old Payments", now.minus(Duration.ofDays(95)), true, true), // has active deps
                    new MonitoringRetentionPolicy.RetiredDashboard("dash-3", "Old Shipping", now.minus(Duration.ofDays(95)), false, true), // held
                    new MonitoringRetentionPolicy.RetiredDashboard("dash-4", "Recent Retire", now.minus(Duration.ofDays(20)), false, true) // <90d
            );

            var decisions = policy.reviewRetiredDashboards(dashboards, now);

            assertThat(decisions).hasSize(4);
            assertThat(decisions).anyMatch(d -> d.dashboardId().equals("dash-1") && d.action() == MonitoringRetentionPolicy.DashboardAction.ELIGIBLE_FOR_REMOVAL);
            assertThat(decisions).anyMatch(d -> d.dashboardId().equals("dash-2") && d.action() == MonitoringRetentionPolicy.DashboardAction.BLOCKED_BY_DEPENDENCY);
            assertThat(decisions).anyMatch(d -> d.dashboardId().equals("dash-3") && d.action() == MonitoringRetentionPolicy.DashboardAction.BLOCKED_BY_HOLD);
            assertThat(decisions).anyMatch(d -> d.dashboardId().equals("dash-4") && d.action() == MonitoringRetentionPolicy.DashboardAction.RETAINED_PENDING_AGE);
        }
    }

    @Nested
    @DisplayName("D14 Mailpit Retention Manager Tests")
    class MailpitRetentionTests {

        private MailpitRetentionManager manager;

        @BeforeEach
        void init() {
            manager = new MailpitRetentionManager(guard);
        }

        @Test
        void prunesMessagesOlderThan30DaysAndKeepsHeldEvidence() {
            guard.registerHold("HOLD-MAIL", OperationalRetentionGuard.Scope.MAILPIT, "msg-old-held",
                    "Evidence in support ticket", ManualCaseProcedureService.ROLE_OWNER, now);

            List<MailpitRetentionManager.MailpitMessage> messages = List.of(
                    new MailpitRetentionManager.MailpitMessage("msg-1", "<msg-1@minitech>", "a@x.com", "b@x.com", "Recent", now.minus(Duration.ofDays(10)), 500, 0, false),
                    new MailpitRetentionManager.MailpitMessage("msg-old", "<msg-old@minitech>", "a@x.com", "b@x.com", "Old", now.minus(Duration.ofDays(35)), 500, 1, false),
                    new MailpitRetentionManager.MailpitMessage("msg-old-held", "<msg-old-held@minitech>", "a@x.com", "b@x.com", "Held", now.minus(Duration.ofDays(35)), 500, 2, true)
            );

            var summary = manager.evaluateAndPrune(messages, now);

            assertThat(summary.prunedCount()).isEqualTo(1);
            assertThat(summary.finalCount()).isEqualTo(2);
            assertThat(summary.blockedByHoldCount()).isEqualTo(1);
            assertThat(summary.prunedMessages()).anyMatch(p -> p.messageId().equals("msg-old") && p.hasAttachments());
        }

        @Test
        void prunesOldestMessagesWhenExceeding1000MessagesCapacity() {
            List<MailpitRetentionManager.MailpitMessage> messages = new ArrayList<>();
            for (int i = 0; i < 1005; i++) {
                messages.add(new MailpitRetentionManager.MailpitMessage("msg-" + i, "<msg-" + i + "@minitech>",
                        "a@x.com", "b@x.com", "Batch " + i, now.minus(Duration.ofMinutes(1005 - i)), 100, 0, false));
            }

            var summary = manager.evaluateAndPrune(messages, now);

            assertThat(summary.finalCount()).isEqualTo(MailpitRetentionManager.MAX_MESSAGES_PER_INSTANCE);
            assertThat(summary.prunedCount()).isEqualTo(5);
            assertThat(summary.prunedMessages()).allMatch(p -> p.reason() == MailpitRetentionManager.PruneReason.COUNT_LIMIT_EXCEEDED);
            // msg-0 was the oldest, so it must be pruned
            assertThat(summary.prunedMessages()).anyMatch(p -> p.messageId().equals("msg-0"));
        }
    }

    @Nested
    @DisplayName("D15/D18 Manual Procedures and Role Controls Tests")
    class ManualProceduresTests {

        private ManualCaseProcedureService caseService;

        @BeforeEach
        void init() {
            caseService = new ManualCaseProcedureService(guard);
        }

        @Test
        void ambiguousCaseReviewRequiresPrimaryOwnerOperator1() {
            caseService.createCase("CASE-100", "chat-conversation-987", now);
            caseService.markAmbiguous("CASE-100");

            // Deputy operator-2 must be rejected for ambiguous case review under P08
            assertThatThrownBy(() -> caseService.resolveAmbiguousCase("CASE-100", ManualCaseProcedureService.ROLE_DEPUTY,
                    ManualCaseProcedureService.CaseDecision.APPROVED, now))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Ambiguous case review requires primary owner (operator-1)");

            // Random operator rejected
            assertThatThrownBy(() -> caseService.resolveAmbiguousCase("CASE-100", "operator-3",
                    ManualCaseProcedureService.CaseDecision.APPROVED, now))
                    .isInstanceOf(SecurityException.class);

            // Primary owner operator-1 succeeds
            var resolved = caseService.resolveAmbiguousCase("CASE-100", ManualCaseProcedureService.ROLE_OWNER,
                    ManualCaseProcedureService.CaseDecision.APPROVED, now);
            assertThat(resolved.status()).isEqualTo(ManualCaseProcedureService.CaseStatus.CLOSED_CONFIRMED);
            assertThat(resolved.decision()).isEqualTo(ManualCaseProcedureService.CaseDecision.APPROVED);
            assertThat(resolved.decidedBy()).isEqualTo(ManualCaseProcedureService.ROLE_OWNER);
        }

        @Test
        void evaluatesCaseRetention90DaysEvidenceAnd365DaysMinimalRecord() {
            caseService.createCase("CASE-200", "account-req-456", now.minus(Duration.ofDays(400)));
            caseService.markAmbiguous("CASE-200");
            caseService.resolveAmbiguousCase("CASE-200", ManualCaseProcedureService.ROLE_OWNER,
                    ManualCaseProcedureService.CaseDecision.APPROVED, now.minus(Duration.ofDays(100)));

            // At 100 days after closure: evidence (90d) should be pruned, minimal record (365d) retained
            var partial = caseService.retentionEligibility("CASE-200", now);
            assertThat(partial.evidenceEligible()).isTrue();
            assertThat(partial.minimumEligible()).isFalse();

            // At 400 days after closure: both evidence and minimal record pruned
            var complete = caseService.retentionEligibility("CASE-200", now.plus(Duration.ofDays(300)));
            assertThat(complete.evidenceEligible()).isTrue();
            assertThat(complete.minimumEligible()).isTrue();
            var unchanged = caseService.evaluateCaseRetention("CASE-200", now.plus(Duration.ofDays(300)));
            assertThat(unchanged.evidencePruned()).isFalse();
            assertThat(unchanged.minimalRecordPruned()).isFalse();
        }

        @Test
        void activeOrDisputedCasesBlockRetentionPruning() {
            caseService.createCase("CASE-300", "subject-777", now.minus(Duration.ofDays(500)));
            // Left OPEN
            var record = caseService.evaluateCaseRetention("CASE-300", now);
            assertThat(record.evidencePruned()).isFalse();
            assertThat(record.minimalRecordPruned()).isFalse();
        }

        @Test
        void scopedWorkerStopExecutedByBothOwnerAndDeputyWithAuditReceipt() {
            var receipt1 = caseService.executeScopedWorkerStop("COMMERCE_REDACTION",
                    ManualCaseProcedureService.ROLE_OWNER, "Spike in candidate anomalies", now);
            assertThat(receipt1.executionTerminated()).isFalse();
            assertThat(receipt1.stoppedByOperator()).isEqualTo(ManualCaseProcedureService.ROLE_OWNER);
            assertThat(caseService.isStopRequested("COMMERCE_REDACTION")).isTrue();
            assertThat(caseService.isWorkerStopped("COMMERCE_REDACTION")).isFalse();

            var receipt2 = caseService.executeScopedWorkerStop("MAILPIT_RETENTION",
                    ManualCaseProcedureService.ROLE_DEPUTY, "Emergency freeze for legal audit", now);
            assertThat(receipt2.executionTerminated()).isFalse();
            assertThat(receipt2.stoppedByOperator()).isEqualTo(ManualCaseProcedureService.ROLE_DEPUTY);
            assertThat(caseService.isStopRequested("MAILPIT_RETENTION")).isTrue();
            assertThat(caseService.isWorkerStopped("MAILPIT_RETENTION")).isFalse();

            // Unauthorized operator rejected
            assertThatThrownBy(() -> caseService.executeScopedWorkerStop("LOG_RETENTION", "guest", "none", now))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("not authorized");
        }
    }
}
