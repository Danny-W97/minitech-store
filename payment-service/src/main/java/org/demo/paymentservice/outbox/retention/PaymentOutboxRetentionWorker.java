package org.demo.paymentservice.outbox.retention;

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
public class PaymentOutboxRetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentOutboxRetentionWorker.class);
    private final PaymentOutboxRetentionRepository repository;
    private final PaymentOutboxRetentionProperties properties;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong(-1), oldestAge = new AtomicLong(-1);
    private final AtomicLong lastSuccess = new AtomicLong(-1), lastCompacted = new AtomicLong(-1);
    private final Counter compacted, succeeded, failed;

    @Autowired
    public PaymentOutboxRetentionWorker(PaymentOutboxRetentionRepository repository, PaymentOutboxRetentionProperties properties, MeterRegistry registry) {
        this(repository, properties, registry, Clock.systemUTC());
    }
    PaymentOutboxRetentionWorker(PaymentOutboxRetentionRepository repository, PaymentOutboxRetentionProperties properties, MeterRegistry registry, Clock clock) {
        this.repository = repository; this.properties = properties; this.clock = clock;
        compacted = registry.counter("payment.outbox.retention.compacted");
        succeeded = registry.counter("payment.outbox.retention.runs", "outcome", "success");
        failed = registry.counter("payment.outbox.retention.runs", "outcome", "failed");
        Gauge.builder("payment.outbox.retention.enabled", properties, p -> p.mode() == PaymentOutboxRetentionProperties.Mode.OFF ? 0 : 1).register(registry);
        Gauge.builder("payment.outbox.retention.compaction.enabled", properties, p -> p.mode() == PaymentOutboxRetentionProperties.Mode.COMPACT ? 1 : 0).register(registry);
        Gauge.builder("payment.outbox.retention.age.seconds", properties, p -> p.retention().getSeconds()).register(registry);
        Gauge.builder("payment.outbox.retention.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("payment.outbox.retention.oldest.eligible.age.seconds", oldestAge, AtomicLong::get).register(registry);
        Gauge.builder("payment.outbox.retention.last.success.timestamp.seconds", lastSuccess, AtomicLong::get).register(registry);
        Gauge.builder("payment.outbox.retention.last.compacted", lastCompacted, AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.retention.interval-ms:600000}",
            initialDelayString = "${outbox.retention.initial-delay-ms:60000}")
    public void cleanup() {
        if (properties.mode() == PaymentOutboxRetentionProperties.Mode.OFF) return;
        Instant now = clock.instant(), cutoff = now.minus(properties.retention());
        try {
            int total = 0;
            if (properties.mode() == PaymentOutboxRetentionProperties.Mode.COMPACT) {
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
            LOG.warn("Payment outbox retention failed ({})", error.getClass().getSimpleName());
        }
    }
}
