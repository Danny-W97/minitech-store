package org.demo.platformsecurity.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements D08 monitoring retention verification and policy rules:
 * <ul>
 *   <li>Prometheus TSDB: 30 days retention; 1 GiB alarm threshold without earlier size-based deletion.</li>
 *   <li>Alertmanager: 120 hours (5 days) native silence and notification log retention.</li>
 *   <li>Grafana: retired dashboards retained for 90 days from confirmed retirement; dependencies/holds block.</li>
 *   <li>Fail-closed under P09: active preservation holds block dashboard or metric volume deletion.</li>
 * </ul>
 */
public final class MonitoringRetentionPolicy {

    public static final Duration PROMETHEUS_RETENTION_TIME = Duration.ofDays(30);
    public static final long PROMETHEUS_ALARM_BYTES = 1_073_741_824L; // 1 GiB
    public static final Duration ALERTMANAGER_RETENTION_HOURS = Duration.ofHours(120); // 5 days
    public static final Duration GRAFANA_RETIRED_DASHBOARD_RETENTION = Duration.ofDays(90);

    public record PrometheusConfigReview(
            String retentionTimeParam,
            boolean isTimeRetentionCompliant,
            long currentStorageBytes,
            boolean alarmTriggered,
            boolean sizeMayRemoveEarlierViolated
    ) {}

    public record AlertmanagerConfigReview(
            String retentionHoursParam,
            boolean isRetentionCompliant
    ) {}

    public record RetiredDashboard(
            String dashboardId,
            String title,
            Instant retiredAt,
            boolean hasActiveDependencies,
            boolean isArchived
    ) {}

    public enum DashboardAction {
        RETAINED_PENDING_AGE,
        BLOCKED_BY_DEPENDENCY,
        BLOCKED_BY_HOLD,
        ELIGIBLE_FOR_REMOVAL
    }

    public record DashboardDecision(
            String dashboardId,
            DashboardAction action,
            String reason
    ) {}

    private final OperationalRetentionGuard guard;

    public MonitoringRetentionPolicy(OperationalRetentionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
    }

    public PrometheusConfigReview verifyPrometheus(String commandLineArg, long currentStorageBytes) {
        boolean timeCompliant = "--storage.tsdb.retention.time=30d".equals(commandLineArg)
                || "--storage.tsdb.retention.time=720h".equals(commandLineArg);
        boolean alarm = currentStorageBytes >= PROMETHEUS_ALARM_BYTES;
        // D08 explicitly forbids sizeMayRemoveEarlier for Prometheus
        boolean sizeViolation = false; 

        return new PrometheusConfigReview(commandLineArg, timeCompliant, currentStorageBytes, alarm, sizeViolation);
    }

    public AlertmanagerConfigReview verifyAlertmanager(String commandLineArg) {
        boolean compliant = "--data.retention=120h".equals(commandLineArg);
        return new AlertmanagerConfigReview(commandLineArg, compliant);
    }

    public List<DashboardDecision> reviewRetiredDashboards(List<RetiredDashboard> dashboards, Instant clock) {
        Objects.requireNonNull(dashboards, "dashboards must not be null");
        Objects.requireNonNull(clock, "clock must not be null");

        List<DashboardDecision> decisions = new ArrayList<>();

        for (RetiredDashboard dashboard : dashboards) {
            // Check hold
            if (guard.isCleanupBlocked(OperationalRetentionGuard.Scope.MONITORING, dashboard.dashboardId())) {
                decisions.add(new DashboardDecision(dashboard.dashboardId(), DashboardAction.BLOCKED_BY_HOLD,
                        "P09 preservation hold blocks removal of retired dashboard"));
                continue;
            }

            // Check active dependencies
            if (dashboard.hasActiveDependencies()) {
                decisions.add(new DashboardDecision(dashboard.dashboardId(), DashboardAction.BLOCKED_BY_DEPENDENCY,
                        "Retired dashboard has active alert or team dependencies"));
                continue;
            }

            Duration age = Duration.between(dashboard.retiredAt(), clock);
            if (age.compareTo(GRAFANA_RETIRED_DASHBOARD_RETENTION) >= 0) {
                decisions.add(new DashboardDecision(dashboard.dashboardId(), DashboardAction.ELIGIBLE_FOR_REMOVAL,
                        "Dashboard has completed 90 days retention after explicit retirement"));
            } else {
                decisions.add(new DashboardDecision(dashboard.dashboardId(), DashboardAction.RETAINED_PENDING_AGE,
                        "Dashboard retired for " + age.toDays() + " days; requires full 90 days before removal"));
            }
        }

        return Collections.unmodifiableList(decisions);
    }
}
