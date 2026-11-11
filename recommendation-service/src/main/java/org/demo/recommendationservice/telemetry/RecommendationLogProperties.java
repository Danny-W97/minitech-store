package org.demo.recommendationservice.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("recommendation.request-log")
public record RecommendationLogProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean cleanupEnabled,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("500") int cleanupBatchSize,
        @DefaultValue("4") int cleanupMaxBatches,
        @DefaultValue("600000") long cleanupIntervalMs,
        @DefaultValue("60000") long cleanupInitialDelayMs) {
    public RecommendationLogProperties {
        if (retention == null || retention.compareTo(Duration.ofSeconds(1)) < 0
                || cleanupBatchSize < 1 || cleanupBatchSize > 1000
                || cleanupMaxBatches < 1 || cleanupMaxBatches > 20
                || cleanupIntervalMs < 1000 || cleanupInitialDelayMs < 0) {
            throw new IllegalArgumentException("Invalid recommendation request-log retention settings");
        }
    }
}
