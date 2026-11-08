package org.demo.platformsecurity.privacy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Implements D07 application and operational log retention rules:
 * <ul>
 *   <li>Maximum age: 30 days from entry write (not rotation, compression, or restart).</li>
 *   <li>Maximum bytes per source including rotations: 100 MiB (104,857,600 bytes).</li>
 *   <li>Size limit may prune oldest rotated segments earlier than 30 days.</li>
 *   <li>Excludes D10 legacy history and certified CC job logs (handled by A43.8).</li>
 *   <li>Fail-closed under P09: active preservation holds block log pruning.</li>
 * </ul>
 */
public final class OperationalLogRetention {

    public static final long MAX_BYTES_PER_SOURCE = 104_857_600L; // 100 MiB
    public static final Duration MAX_LOG_AGE = Duration.ofDays(30);

    public record LogFileMetadata(
            Path path,
            String sourceName,
            long sizeBytes,
            Instant entryWrittenAt,
            boolean isRotatedSegment,
            boolean isLegacyD10,
            boolean isCertifiedJobLog
    ) {}

    public enum RetentionAction {
        RETAINED,
        PRUNED_BY_AGE,
        PRUNED_BY_SIZE,
        BLOCKED_BY_HOLD,
        PROTECTED_LEGACY,
        PROTECTED_CERTIFIED
    }

    public record RetentionDecision(
            Path path,
            RetentionAction action,
            String reason
    ) {}

    public record ExecutionReport(
            String sourceName,
            long initialSizeBytes,
            long finalSizeBytes,
            int totalFilesEvaluated,
            int filesPruned,
            int filesBlocked,
            List<RetentionDecision> decisions
    ) {}

    private final OperationalRetentionGuard guard;

    public OperationalLogRetention(OperationalRetentionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
    }

    /**
     * Evaluates and applies retention to log segments for a given log source.
     *
     * @param sourceName identifier of the log source (e.g. "order-service", "cc-http")
     * @param files      all segments/files for this source in the repository
     * @param clock      current simulation or system timestamp
     * @param dryRun     if true, does not delete files from disk
     * @return ExecutionReport with full audit trail of actions taken
     */
    public ExecutionReport processSource(
            String sourceName,
            List<LogFileMetadata> files,
            Instant clock,
            boolean dryRun
    ) {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(files, "files must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (!dryRun) throw new IllegalStateException("OPERATIONAL_RETENTION_EXECUTION_NOT_CONFIGURED");

        synchronized (guard) {

        List<RetentionDecision> decisions = new ArrayList<>();
        long currentTotalBytes = 0;
        int prunedCount = 0;
        int blockedCount = 0;

        List<LogFileMetadata> activeSourceFiles = new ArrayList<>();

        for (LogFileMetadata file : files) {
            currentTotalBytes += file.sizeBytes();

            if (file.isLegacyD10()) {
                decisions.add(new RetentionDecision(file.path(), RetentionAction.PROTECTED_LEGACY,
                        "D10 legacy history is excluded from automated deletion"));
                activeSourceFiles.add(file);
                continue;
            }

            if (file.isCertifiedJobLog()) {
                decisions.add(new RetentionDecision(file.path(), RetentionAction.PROTECTED_CERTIFIED,
                        "Certified CC job logs are governed by dedicated A43.8 protocol"));
                activeSourceFiles.add(file);
                continue;
            }

            // Check P09 preservation hold
            if (guard.isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, file.path().toString())
                    || guard.isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, sourceName)) {
                decisions.add(new RetentionDecision(file.path(), RetentionAction.BLOCKED_BY_HOLD,
                        "P09 preservation hold or unresolved exception blocks log cleanup"));
                blockedCount++;
                activeSourceFiles.add(file);
                continue;
            }

            // Check age rule: 30 days from entry write
            Duration age = Duration.between(file.entryWrittenAt(), clock);
            if (age.compareTo(MAX_LOG_AGE) > 0) {
                decisions.add(new RetentionDecision(file.path(), RetentionAction.PRUNED_BY_AGE,
                        "Log entry age (" + age.toDays() + " days) exceeds maximum 30 days"));
                prunedCount++;
                currentTotalBytes -= file.sizeBytes();
            } else {
                activeSourceFiles.add(file);
            }
        }

        // Check size rule: 100 MiB limit per source; prune oldest rotated segments if exceeded
        if (currentTotalBytes > MAX_BYTES_PER_SOURCE) {
            // Sort remaining active rotated segments by age (oldest entryWrittenAt first)
            List<LogFileMetadata> candidatesForSizePrune = activeSourceFiles.stream()
                    .filter(LogFileMetadata::isRotatedSegment)
                    .filter(f -> !f.isLegacyD10() && !f.isCertifiedJobLog())
                    .filter(f -> !guard.isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, f.path().toString()))
                    .filter(f -> !guard.isCleanupBlocked(OperationalRetentionGuard.Scope.LOGS, sourceName))
                    .sorted(Comparator.comparing(LogFileMetadata::entryWrittenAt))
                    .toList();

            for (LogFileMetadata candidate : candidatesForSizePrune) {
                if (currentTotalBytes <= MAX_BYTES_PER_SOURCE) {
                    break;
                }
                decisions.add(new RetentionDecision(candidate.path(), RetentionAction.PRUNED_BY_SIZE,
                        "Source size (" + currentTotalBytes + " bytes) exceeds 100 MiB limit; pruning oldest rotated segment"));
                currentTotalBytes -= candidate.sizeBytes();
                prunedCount++;
            }
        }

        long initialTotal = files.stream().mapToLong(LogFileMetadata::sizeBytes).sum();
        return new ExecutionReport(sourceName, initialTotal, currentTotalBytes, files.size(), prunedCount, blockedCount, Collections.unmodifiableList(decisions));
        }
    }

}
