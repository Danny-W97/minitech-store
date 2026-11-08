package org.demo.platformsecurity;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PersistentQueueMetricsTest {
    final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    double value(String metric, String queue) { return registry.get(metric).tag("queue", queue).gauge().value(); }
    PersistentQueueMetrics metrics() {
        jdbc.execute("CREATE TABLE work (status VARCHAR(20), created_at TIMESTAMP)");
        var metrics = new PersistentQueueMetrics(jdbc, List.of(new PersistentQueueMetrics.Queue("test", "SELECT COUNT(*), MIN(created_at) FROM work WHERE status='PENDING'")), clock);
        metrics.bindTo(registry);
        return metrics;
    }
    @Test void oldestPendingSurvivesRetryAndResolvedWorkIsExcluded() {
        var metrics = metrics();
        jdbc.update("INSERT INTO work VALUES ('PENDING', ?), ('DONE', ?)", Timestamp.from(clock.instant().minusSeconds(400)), Timestamp.from(clock.instant().minusSeconds(900)));
        metrics.collect();
        assertEquals(1, value("platform.business.queue.count", "test"));
        assertEquals(400, value("platform.business.queue.oldest.age.seconds", "test"));
        assertEquals(1, value("platform.business.collector.up", "test"));
        jdbc.execute("UPDATE work SET status='DONE'"); metrics.collect();
        assertEquals(0, value("platform.business.queue.count", "test"));
        assertEquals(0, value("platform.business.queue.oldest.age.seconds", "test"));
    }
    @Test void failedCollectionRetainsStateAndScrapingNeedsNoSql() {
        var metrics = metrics();
        assertEquals(0, value("platform.business.collector.up", "test"));
        jdbc.update("INSERT INTO work VALUES ('PENDING', ?)", Timestamp.from(clock.instant().minusSeconds(700)));
        metrics.collect(); double collected = value("platform.business.collector.last.success.timestamp.seconds", "test");
        jdbc.execute("DROP TABLE work"); metrics.collect();
        assertEquals(0, value("platform.business.collector.up", "test"));
        assertEquals(collected, value("platform.business.collector.last.success.timestamp.seconds", "test"));
        assertEquals(1, value("platform.business.queue.count", "test"));
        assertEquals(700, value("platform.business.queue.oldest.age.seconds", "test"));
        jdbc.execute("CREATE TABLE work (status VARCHAR(20), created_at TIMESTAMP)"); metrics.collect();
        assertEquals(1, value("platform.business.collector.up", "test"));
        assertEquals(0, value("platform.business.queue.count", "test"));
    }
    @Test void aBrokenQueueDoesNotSuppressOtherQueues() {
        var metrics = new PersistentQueueMetrics(jdbc, List.of(
                new PersistentQueueMetrics.Queue("broken", "SELECT COUNT(*), MIN(created_at) FROM missing"),
                new PersistentQueueMetrics.Queue("valid", "SELECT 0, CAST(NULL AS TIMESTAMP)")), clock);
        metrics.bindTo(registry); metrics.collect();
        assertEquals(0, value("platform.business.collector.up", "broken"));
        assertEquals(1, value("platform.business.collector.up", "valid"));
    }
}
