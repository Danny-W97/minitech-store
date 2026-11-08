package org.demo.customerservice.retention;

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
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SecurityRetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityRetentionWorker.class);
    private static class Sample {
        final AtomicLong pending = new AtomicLong(-1), oldestAge = new AtomicLong(-1);
        final AtomicLong lastSuccess = new AtomicLong(-1), lastChanged = new AtomicLong(-1);
        final Counter changed, succeeded, failed;
        Sample(SecurityRetentionRepository.Store store, MeterRegistry registry) {
            String tag = store.metricTag();
            changed = registry.counter("customer.security.retention." + (store.clearsFields() ? "cleared" : "deleted"), "store", tag);
            succeeded = registry.counter("customer.security.retention.runs", "store", tag, "outcome", "success");
            failed = registry.counter("customer.security.retention.runs", "store", tag, "outcome", "failed");
            Gauge.builder("customer.security.retention.pending", pending, AtomicLong::get).tag("store", tag).register(registry);
            Gauge.builder("customer.security.retention.oldest.eligible.age.seconds", oldestAge, AtomicLong::get).tag("store", tag).register(registry);
            Gauge.builder("customer.security.retention.last.success.timestamp.seconds", lastSuccess, AtomicLong::get).tag("store", tag).register(registry);
            Gauge.builder("customer.security.retention.last." + (store.clearsFields() ? "cleared" : "deleted"), lastChanged, AtomicLong::get).tag("store", tag).register(registry);
        }
    }
    private final SecurityRetentionRepository repository;
    private final SecurityRetentionProperties properties;
    private final Clock clock;
    private final EnumMap<SecurityRetentionRepository.Store, Sample> samples = new EnumMap<>(SecurityRetentionRepository.Store.class);

    @Autowired
    public SecurityRetentionWorker(SecurityRetentionRepository repository, SecurityRetentionProperties properties, MeterRegistry registry) {
        this(repository, properties, registry, Clock.systemUTC());
    }
    SecurityRetentionWorker(SecurityRetentionRepository repository, SecurityRetentionProperties properties, MeterRegistry registry, Clock clock) {
        this.repository = repository; this.properties = properties; this.clock = clock;
        for (var store : SecurityRetentionRepository.Store.values()) samples.put(store, new Sample(store, registry));
        Gauge.builder("customer.security.retention.enabled", properties, p -> p.enabled() ? 1 : 0).register(registry);
        Gauge.builder("customer.security.retention.age.seconds", properties, p -> p.retention().getSeconds()).register(registry);
    }

    @Scheduled(fixedDelayString = "${customer.security-retention.interval-ms:600000}",
            initialDelayString = "${customer.security-retention.initial-delay-ms:60000}")
    public void cleanup() {
        if (!properties.enabled()) return;
        Instant now = clock.instant(), cutoff = now.minus(properties.retention());
        for (var store : SecurityRetentionRepository.Store.values()) {
            Sample sample = samples.get(store);
            try {
                int total = 0;
                for (int batch = 0; batch < properties.maxBatchesPerStore(); batch++) {
                    int count = repository.cleanupBatch(store, cutoff, properties.batchSize());
                    total += count; sample.changed.increment(count);
                    if (count < properties.batchSize()) break;
                }
                Instant oldest = repository.oldestEligible(store, cutoff);
                sample.pending.set(oldest == null ? 0 : 1);
                sample.oldestAge.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds()));
                sample.lastChanged.set(total); sample.lastSuccess.set(now.getEpochSecond()); sample.succeeded.increment();
            } catch (DataAccessException | TransactionException error) {
                sample.failed.increment();
                LOG.warn("Security retention failed for {} ({})", store.metricTag(), error.getClass().getSimpleName());
            }
        }
    }
}
