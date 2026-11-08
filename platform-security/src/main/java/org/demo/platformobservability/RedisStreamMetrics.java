package org.demo.platformobservability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/** Read-only payment stream group metadata, collected off the scrape path.
 * Labels are fixed roles, never configured Redis names, consumer IDs or payloads. */
public final class RedisStreamMetrics implements MeterBinder {
    public enum Recipient { BILLING, NOTIFICATION }
    private record Snapshot(double present, double pending, double lag, double collected, boolean up) {
        static Snapshot unknown(double lastSuccess) {
            return new Snapshot(Double.NaN, Double.NaN, Double.NaN, lastSuccess, false);
        }
    }
    private final StringRedisTemplate redis;
    private final String stream;
    private final String group;
    private final String recipient;
    private final Clock clock;
    private volatile Snapshot snapshot = Snapshot.unknown(0);

    public RedisStreamMetrics(StringRedisTemplate redis, String stream, String group, Recipient recipient) {
        this(redis, stream, group, recipient, Clock.systemUTC());
    }

    public RedisStreamMetrics(StringRedisTemplate redis, String stream, String group, Recipient recipient, Clock clock) {
        this.redis = Objects.requireNonNull(redis);
        this.stream = Objects.requireNonNull(stream);
        this.group = Objects.requireNonNull(group);
        this.recipient = Objects.requireNonNull(recipient).name().toLowerCase(Locale.ROOT);
        this.clock = Objects.requireNonNull(clock);
        if (stream.isBlank() || group.isBlank()) throw new IllegalArgumentException("Stream and group are required");
    }

    @Scheduled(fixedDelayString = "${observability.streams.fixed-delay-ms:30000}",
            initialDelayString = "${observability.streams.fixed-delay-ms:30000}")
    public synchronized void collect() {
        try {
            var groups = Objects.requireNonNull(redis.opsForStream().groups(stream)); // XINFO GROUPS only.
            XInfoGroup selected = null;
            for (var candidate : groups) {
                if (group.equals(candidate.groupName())) {
                    if (selected != null) throw new IllegalStateException("Duplicate group metadata");
                    selected = candidate;
                }
            }
            double now = clock.millis() / 1000d;
            if (selected == null) {
                snapshot = new Snapshot(0, Double.NaN, Double.NaN, now, true);
            } else {
                double pending = count(selected.pendingCount());
                Object lag = selected.getRaw().get("lag");
                snapshot = new Snapshot(1, pending, lag == null ? Double.NaN : count(lag), now, true);
            }
        } catch (RuntimeException unavailable) {
            // A missing stream, ACL denial or outage is unknown, never an empty healthy queue.
            snapshot = Snapshot.unknown(snapshot.collected());
        }
    }

    private static double count(Object value) {
        if (!(value instanceof Long number) || number < 0)
            throw new IllegalStateException("Invalid stream count metadata");
        return number.doubleValue();
    }

    @Override public void bindTo(MeterRegistry registry) {
        gauge(registry, "pending", m -> m.snapshot.pending());
        gauge(registry, "lag", m -> m.snapshot.lag());
        gauge(registry, "lag.known", m -> Double.isNaN(m.snapshot.lag()) ? 0 : 1);
        gauge(registry, "group.present", m -> m.snapshot.present());
        gauge(registry, "collector.up", m -> m.snapshot.up() ? 1 : 0);
        gauge(registry, "collector.last.success.timestamp.seconds", m -> m.snapshot.collected());
    }

    private void gauge(MeterRegistry registry, String suffix, java.util.function.ToDoubleFunction<RedisStreamMetrics> value) {
        Gauge.builder("platform.redis.stream." + suffix, this, value)
                .tag("stream", "payments").tag("recipient", recipient).register(registry);
    }
}
