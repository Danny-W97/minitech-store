package org.demo.recommendationservice.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.demo.recommendationservice.recommendation.RecommendationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RecommendationLogCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(RecommendationLogCleanup.class);
    private final RecommendationRequestRepository repository;
    private final RecommendationLogProperties properties;
    private final Clock clock;
    private final Counter deleted, succeeded, failed;
    private final AtomicLong oldestAge = new AtomicLong(-1), pending = new AtomicLong(-1);
    private final AtomicLong lastSuccess = new AtomicLong(-1), lastDeleted = new AtomicLong(-1);

    @Autowired
    public RecommendationLogCleanup(RecommendationRequestRepository repository,
                                    RecommendationLogProperties properties, MeterRegistry registry) {
        this(repository, properties, registry, Clock.systemUTC());
    }

    RecommendationLogCleanup(RecommendationRequestRepository repository,
                              RecommendationLogProperties properties, MeterRegistry registry, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        deleted = registry.counter("recommendation.request.log.deleted");
        succeeded = registry.counter("recommendation.request.log.cleanup.runs", "outcome", "success");
        failed = registry.counter("recommendation.request.log.cleanup.runs", "outcome", "failed");
        Gauge.builder("recommendation.request.log.oldest.age.seconds", oldestAge, AtomicLong::get).register(registry);
        Gauge.builder("recommendation.request.log.cleanup.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("recommendation.request.log.cleanup.last.success.timestamp.seconds", lastSuccess, AtomicLong::get).register(registry);
        Gauge.builder("recommendation.request.log.cleanup.last.deleted", lastDeleted, AtomicLong::get).register(registry);
        Gauge.builder("recommendation.request.log.cleanup.enabled", properties, p -> p.cleanupEnabled() ? 1 : 0).register(registry);
        Gauge.builder("recommendation.request.log.retention.seconds", properties, p -> p.retention().getSeconds()).register(registry);
    }

    @Scheduled(fixedDelayString = "${recommendation.request-log.cleanup-interval-ms:600000}",
            initialDelayString = "${recommendation.request-log.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        if (!properties.cleanupEnabled()) return;
        Instant now = clock.instant(), cutoff = now.minus(properties.retention());
        int removed = 0;
        try {
            // Each delete is a separate committed batch. Replicas skip rows held by others.
            for (int batch = 0; batch < properties.cleanupMaxBatches(); batch++) {
                int count = repository.deleteExpiredBatch(cutoff, properties.cleanupBatchSize());
                removed += count;
                deleted.increment(count);
                if (count < properties.cleanupBatchSize()) break;
            }
            Instant oldest = repository.oldestRequestedAt();
            oldestAge.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds()));
            pending.set(oldest != null && oldest.isBefore(cutoff) ? 1 : 0);
            lastDeleted.set(removed);
            lastSuccess.set(now.getEpochSecond());
            succeeded.increment();
        } catch (DataAccessException failure) {
            failed.increment();
            LOG.warn("Recommendation request-log cleanup failed ({})", failure.getClass().getSimpleName());
        }
    }
}
