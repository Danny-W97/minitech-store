package org.demo.customerservice.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("customer.security-retention")
public record SecurityRetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("100") int batchSize,
        @DefaultValue("4") int maxBatchesPerStore,
        @DefaultValue("600000") long intervalMs,
        @DefaultValue("60000") long initialDelayMs) {
    public SecurityRetentionProperties {
        if (retention == null || retention.compareTo(Duration.ofSeconds(1)) < 0
                || batchSize < 1 || batchSize > 1000 || maxBatchesPerStore < 1 || maxBatchesPerStore > 20
                || intervalMs < 1000 || initialDelayMs < 0) {
            throw new IllegalArgumentException("Invalid customer security-retention settings");
        }
    }
}
