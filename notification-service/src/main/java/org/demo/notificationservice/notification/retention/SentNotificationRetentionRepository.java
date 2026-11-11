package org.demo.notificationservice.notification.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

@Repository
public class SentNotificationRetentionRepository {
    private static final String ELIGIBLE = """
            status = 'SENT' AND sent_at IS NOT NULL AND content_compacted_at IS NULL
            AND subject IS NOT NULL AND message IS NOT NULL AND next_attempt_at IS NULL
            AND failure_reason IS NULL AND processing_token IS NULL AND processing_until IS NULL
            AND GREATEST(sent_at, created_at, updated_at) < :cutoff
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SentNotificationRetentionRepository(DataSource datasource, PlatformTransactionManager manager) {
        var template = new JdbcTemplate(datasource);template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);
    }
    public int compactBatch(Instant cutoff, Instant compactedAt, int batchSize) {
        Objects.requireNonNull(cutoff);Objects.requireNonNull(compactedAt);
        if (batchSize < 1 || batchSize > 1000 || compactedAt.isBefore(cutoff))
            throw new IllegalArgumentException("Invalid sent notification compaction batch");
        return Objects.requireNonNull(transaction.execute(status -> jdbc.update(
                "WITH candidates AS MATERIALIZED (SELECT id FROM notifications WHERE " + ELIGIBLE
                        + " ORDER BY GREATEST(sent_at, created_at, updated_at), id LIMIT :limit FOR UPDATE SKIP LOCKED) "
                        + "UPDATE notifications SET subject = NULL, message = NULL, content_compacted_at = :now WHERE " + ELIGIBLE
                        + " AND id IN (SELECT id FROM candidates)",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff))
                        .addValue("now",Timestamp.from(compactedAt)).addValue("limit",batchSize))));
    }
    public Instant oldestEligible(Instant cutoff) {
        var rows=jdbc.query("SELECT GREATEST(sent_at, created_at, updated_at) AS age FROM notifications WHERE " + ELIGIBLE
                        + " ORDER BY GREATEST(sent_at, created_at, updated_at), id LIMIT 1",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff)),(row,index)->row.getTimestamp("age").toInstant());
        return rows.isEmpty()?null:rows.getFirst();
    }
}
