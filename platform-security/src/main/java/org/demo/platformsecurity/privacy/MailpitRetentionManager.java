package org.demo.platformsecurity.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Implements D14 developer mail (Mailpit) retention manager rules:
 * <ul>
 *   <li>Maximum age: 30 days from message receipt.</li>
 *   <li>Maximum capacity: 1000 messages per instance.</li>
 *   <li>Count limit may prune oldest messages along with attachments earlier than 30 days.</li>
 *   <li>Fail-closed under P09: messages tagged with preservation holds or active case IDs cannot be pruned.</li>
 * </ul>
 */
public final class MailpitRetentionManager {

    public static final int MAX_MESSAGES_PER_INSTANCE = 1000;
    public static final Duration MAX_MESSAGE_AGE = Duration.ofDays(30);

    public record MailpitMessage(
            String id,
            String messageIdHeader,
            String sender,
            String recipient,
            String subject,
            Instant receivedAt,
            long sizeBytes,
            int attachmentCount,
            boolean isCaseEvidence
    ) {}

    public enum PruneReason {
        AGE_EXCEEDED,
        COUNT_LIMIT_EXCEEDED
    }

    public record PruneResult(
            String messageId,
            PruneReason reason,
            boolean hasAttachments
    ) {}

    public record ExecutionSummary(
            int initialCount,
            int finalCount,
            int prunedCount,
            int blockedByHoldCount,
            List<PruneResult> prunedMessages
    ) {}

    private final OperationalRetentionGuard guard;

    public MailpitRetentionManager(OperationalRetentionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
    }

    public ExecutionSummary evaluateAndPrune(List<MailpitMessage> messages, Instant clock) {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(clock, "clock must not be null");

        List<PruneResult> pruned = new ArrayList<>();
        List<MailpitMessage> retained = new ArrayList<>();
        int blockedCount = 0;

        // Phase 1: Evaluate Age (30 days from receipt) with P09 hold protection
        for (MailpitMessage msg : messages) {
            boolean isBlocked = msg.isCaseEvidence()
                    || guard.isCleanupBlocked(OperationalRetentionGuard.Scope.MAILPIT, msg.id())
                    || guard.isCleanupBlocked(OperationalRetentionGuard.Scope.MAILPIT, msg.messageIdHeader());

            if (isBlocked) {
                blockedCount++;
                retained.add(msg);
                continue;
            }

            Duration age = Duration.between(msg.receivedAt(), clock);
            if (age.compareTo(MAX_MESSAGE_AGE) > 0) {
                pruned.add(new PruneResult(msg.id(), PruneReason.AGE_EXCEEDED, msg.attachmentCount() > 0));
            } else {
                retained.add(msg);
            }
        }

        // Phase 2: Evaluate Count Limit (1000 messages); prune oldest eligible retained messages
        if (retained.size() > MAX_MESSAGES_PER_INSTANCE) {
            // Sort eligible (non-held) messages oldest first
            List<MailpitMessage> eligibleForCountPrune = retained.stream()
                    .filter(m -> !m.isCaseEvidence()
                            && !guard.isCleanupBlocked(OperationalRetentionGuard.Scope.MAILPIT, m.id())
                            && !guard.isCleanupBlocked(OperationalRetentionGuard.Scope.MAILPIT, m.messageIdHeader()))
                    .sorted(Comparator.comparing(MailpitMessage::receivedAt))
                    .toList();

            List<MailpitMessage> remainingRetained = new ArrayList<>(retained);
            for (MailpitMessage candidate : eligibleForCountPrune) {
                if (remainingRetained.size() <= MAX_MESSAGES_PER_INSTANCE) {
                    break;
                }
                pruned.add(new PruneResult(candidate.id(), PruneReason.COUNT_LIMIT_EXCEEDED, candidate.attachmentCount() > 0));
                remainingRetained.remove(candidate);
            }
            retained = remainingRetained;
        }

        return new ExecutionSummary(messages.size(), retained.size(), pruned.size(), blockedCount, Collections.unmodifiableList(pruned));
    }
}
