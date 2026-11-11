package org.demo.notificationservice.messaging.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RejectedInboxRetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(RejectedInboxRetentionWorker.class);
    private final RejectedInboxRetentionRepository repository;
    private final RejectedInboxRetentionProperties properties;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong(-1), oldestAge = new AtomicLong(-1);
    private final AtomicLong lastSuccess = new AtomicLong(-1), lastCompacted = new AtomicLong(-1);
    private final Counter compacted, succeeded, failed;

    @Autowired
    public RejectedInboxRetentionWorker(RejectedInboxRetentionRepository repository, RejectedInboxRetentionProperties properties, MeterRegistry registry) {
        this(repository, properties, registry, Clock.systemUTC());
    }
    RejectedInboxRetentionWorker(RejectedInboxRetentionRepository repository, RejectedInboxRetentionProperties properties, MeterRegistry registry, Clock clock) {
        this.repository = repository; this.properties = properties; this.clock = clock;
        compacted = registry.counter("notification.rejected.inbox.retention.compacted");
        succeeded = registry.counter("notification.rejected.inbox.retention.runs", "outcome", "success");
        failed = registry.counter("notification.rejected.inbox.retention.runs", "outcome", "failed");
        Gauge.builder("notification.rejected.inbox.retention.enabled", properties, p -> p.mode() == RejectedInboxRetentionProperties.Mode.OFF ? 0 : 1).register(registry);
        Gauge.builder("notification.rejected.inbox.retention.compaction.enabled", properties, p -> p.mode() == RejectedInboxRetentionProperties.Mode.COMPACT ? 1 : 0).register(registry);
        Gauge.builder("notification.rejected.inbox.retention.age.seconds", properties, p -> p.retention().getSeconds()).register(registry);
        Gauge.builder("notification.rejected.inbox.retention.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("notification.rejected.inbox.retention.oldest.eligible.age.seconds", oldestAge, AtomicLong::get).register(registry);
        Gauge.builder("notification.rejected.inbox.retention.last.success.timestamp.seconds", lastSuccess, AtomicLong::get).register(registry);
        Gauge.builder("notification.rejected.inbox.retention.last.compacted", lastCompacted, AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString = "${messaging.rejected-inbox-retention.interval-ms:600000}",
            initialDelayString = "${messaging.rejected-inbox-retention.initial-delay-ms:60000}")
    public void cleanup() {
        if (properties.mode() == RejectedInboxRetentionProperties.Mode.OFF) return;
        Instant now = clock.instant(), cutoff = now.minus(properties.retention());
        try {
            int total = 0;
            if (properties.mode() == RejectedInboxRetentionProperties.Mode.COMPACT) {
                for (int batch = 0; batch < properties.maxBatches(); batch++) {
                    int count = repository.compactBatch(cutoff, now, properties.batchSize());
                    total += count; compacted.increment(count);
                    if (count < properties.batchSize()) break;
                }
            }
            Instant oldest = repository.oldestEligible(cutoff);
            pending.set(oldest == null ? 0 : 1);
            oldestAge.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds()));
            lastCompacted.set(total); lastSuccess.set(now.getEpochSecond()); succeeded.increment();
        } catch (DataAccessException | TransactionException error) {
            failed.increment();
            // Avoid logging SQL, actor, metadata, or the exception message.
            LOG.warn("Rejected inbox retention failed ({})", error.getClass().getSimpleName());
        }
    }
}
