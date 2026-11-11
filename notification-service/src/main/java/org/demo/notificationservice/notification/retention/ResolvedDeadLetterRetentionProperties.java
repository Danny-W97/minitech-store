package org.demo.notificationservice.notification.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("notification.resolved-dlq-retention")
public record ResolvedDeadLetterRetentionProperties(
        @DefaultValue("observe") Mode mode,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("500") int batchSize,
        @DefaultValue("4") int maxBatches,
        @DefaultValue("600000") long intervalMs,
        @DefaultValue("60000") long initialDelayMs) {
    public enum Mode {OFF, OBSERVE, COMPACT}
    public ResolvedDeadLetterRetentionProperties {
        if (mode == null || retention == null || retention.compareTo(Duration.ofDays(1)) < 0
                || batchSize < 1 || batchSize > 1000 || maxBatches < 1 || maxBatches > 20
                || intervalMs < 1000 || initialDelayMs < 0) {
            throw new IllegalArgumentException("Invalid resolved dead-letter retention settings");
        }
    }
}
