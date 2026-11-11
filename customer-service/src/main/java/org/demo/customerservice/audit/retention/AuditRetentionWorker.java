package org.demo.customerservice.audit.retention;

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
public class AuditRetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRetentionWorker.class);
    private final AuditRetentionRepository repository;
    private final AuditRetentionProperties properties;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong(-1), oldestAge = new AtomicLong(-1);
    private final AtomicLong lastSuccess = new AtomicLong(-1), lastDeleted = new AtomicLong(-1);
    private final Counter deleted, succeeded, failed;

    @Autowired
    public AuditRetentionWorker(AuditRetentionRepository repository, AuditRetentionProperties properties, MeterRegistry registry) {
        this(repository, properties, registry, Clock.systemUTC());
    }
    AuditRetentionWorker(AuditRetentionRepository repository, AuditRetentionProperties properties, MeterRegistry registry, Clock clock) {
        this.repository = repository; this.properties = properties; this.clock = clock;
        deleted = registry.counter("customer.audit.retention.deleted");
        succeeded = registry.counter("customer.audit.retention.runs", "outcome", "success");
        failed = registry.counter("customer.audit.retention.runs", "outcome", "failed");
        Gauge.builder("customer.audit.retention.enabled", properties, p -> p.mode() == AuditRetentionProperties.Mode.OFF ? 0 : 1).register(registry);
        Gauge.builder("customer.audit.retention.purge.enabled", properties, p -> p.mode() == AuditRetentionProperties.Mode.PURGE ? 1 : 0).register(registry);
        Gauge.builder("customer.audit.retention.age.seconds", properties, p -> p.retention().getSeconds()).register(registry);
        Gauge.builder("customer.audit.retention.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("customer.audit.retention.oldest.eligible.age.seconds", oldestAge, AtomicLong::get).register(registry);
        Gauge.builder("customer.audit.retention.last.success.timestamp.seconds", lastSuccess, AtomicLong::get).register(registry);
        Gauge.builder("customer.audit.retention.last.deleted", lastDeleted, AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString = "${customer.audit-retention.interval-ms:600000}",
            initialDelayString = "${customer.audit-retention.initial-delay-ms:60000}")
    public void cleanup() {
        if (properties.mode() == AuditRetentionProperties.Mode.OFF) return;
        Instant now = clock.instant(), cutoff = now.minus(properties.retention());
        try {
            int total = 0;
            if (properties.mode() == AuditRetentionProperties.Mode.PURGE) {
                for (int batch = 0; batch < properties.maxBatches(); batch++) {
                    int count = repository.deleteBatch(cutoff, properties.batchSize());
                    total += count; deleted.increment(count);
                    if (count < properties.batchSize()) break;
                }
            }
            Instant oldest = repository.oldestEligible(cutoff);
            pending.set(oldest == null ? 0 : 1);
            oldestAge.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds()));
            lastDeleted.set(total); lastSuccess.set(now.getEpochSecond()); succeeded.increment();
        } catch (DataAccessException | TransactionException error) {
            failed.increment();
            // Avoid logging SQL, actor, metadata, or the exception message.
            LOG.warn("Audit retention failed ({})", error.getClass().getSimpleName());
        }
    }
}
