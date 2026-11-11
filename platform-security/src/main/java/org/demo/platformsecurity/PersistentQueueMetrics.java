package org.demo.platformsecurity;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/** Bounded background SQL collection; scraping never acquires a database connection.
 * Labels are fixed queue names, never customer/order IDs or message payloads. */
public final class PersistentQueueMetrics implements MeterBinder {
    public record Queue(String name, String sql) {}
    private record Snapshot(long count, double oldest, double collected, boolean success) {}
    private static final class Sample {
        final Queue queue;
        volatile Snapshot snapshot = new Snapshot(0, 0, 0, false);
        Sample(Queue queue) { this.queue = queue; }
    }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final List<Sample> samples;

    public PersistentQueueMetrics(JdbcTemplate jdbc, List<Queue> queues) {
        this(jdbc, queues, Clock.systemUTC());
    }
    public PersistentQueueMetrics(JdbcTemplate jdbc, List<Queue> queues, Clock clock) {
        // A private template avoids changing timeouts for business operations.
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3);
        this.clock = clock;
        this.samples = queues.stream().map(Sample::new).toList();
        if (queues.stream().map(Queue::name).distinct().count() != queues.size())
            throw new IllegalArgumentException("Duplicate queue metric name");
    }
    @Scheduled(fixedDelayString = "${observability.queues.fixed-delay-ms:30000}")
    public void collect() {
        for (Sample sample : samples) {
            try {
                Snapshot next = jdbc.query(sample.queue.sql(), rows -> {
                    if (!rows.next()) throw new IllegalStateException("Missing aggregate row");
                    long count = rows.getLong(1);
                    Timestamp oldest = rows.getTimestamp(2);
                    return new Snapshot(count, oldest == null ? 0 : oldest.toInstant().toEpochMilli() / 1000d,
                            now(), true);
                });
                sample.snapshot = next;
            } catch (RuntimeException unavailable) {
                // Retain the last value; explicit health/freshness prevents a false recovery.
                Snapshot previous = sample.snapshot;
                sample.snapshot = new Snapshot(previous.count(), previous.oldest(), previous.collected(), false);
            }
        }
    }
    private double now() { return clock.millis() / 1000d; }
    @Override public void bindTo(MeterRegistry registry) {
        for (Sample sample : samples) {
            Gauge.builder("platform.business.queue.count", sample, s -> s.snapshot.count())
                    .tag("queue", sample.queue.name()).register(registry);
            Gauge.builder("platform.business.queue.oldest.age.seconds", sample, s -> {
                Snapshot value = s.snapshot;
                return value.count() == 0 ? 0 : Math.max(0, now() - value.oldest());
            }).tag("queue", sample.queue.name()).register(registry);
            Gauge.builder("platform.business.collector.up", sample, s -> s.snapshot.success() ? 1 : 0)
                    .tag("queue", sample.queue.name()).register(registry);
            Gauge.builder("platform.business.collector.last.success.timestamp.seconds", sample, s -> s.snapshot.collected())
                    .tag("queue", sample.queue.name()).register(registry);
        }
    }
}
