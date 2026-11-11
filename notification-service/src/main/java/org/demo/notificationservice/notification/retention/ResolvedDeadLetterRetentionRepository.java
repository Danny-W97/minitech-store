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
public class ResolvedDeadLetterRetentionRepository {
    private static final String ELIGIBLE = """
            d.status = 'RESOLVED' AND d.resolved_at IS NOT NULL AND d.content_compacted_at IS NULL
            AND d.subject IS NOT NULL AND d.message IS NOT NULL AND d.failure_reason IS NOT NULL
            AND GREATEST(d.resolved_at, d.created_at, d.updated_at) < :cutoff
            AND EXISTS (SELECT 1 FROM notifications n WHERE n.id = d.notification_id
                AND n.status = 'SENT' AND n.sent_at IS NOT NULL AND n.next_attempt_at IS NULL
                AND n.failure_reason IS NULL AND n.processing_token IS NULL AND n.processing_until IS NULL
                AND GREATEST(n.sent_at, n.created_at, n.updated_at) < :cutoff)
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public ResolvedDeadLetterRetentionRepository(DataSource datasource, PlatformTransactionManager manager) {
        var template=new JdbcTemplate(datasource);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);
    }
    public int compactBatch(Instant cutoff, Instant compactedAt, int batchSize) {
        Objects.requireNonNull(cutoff);Objects.requireNonNull(compactedAt);
        if(batchSize<1 || batchSize>1000 || compactedAt.isBefore(cutoff)) throw new IllegalArgumentException("Invalid resolved dead-letter compaction batch");
        return Objects.requireNonNull(transaction.execute(status -> jdbc.update(
                // Only child rows are locked; the parent is observed and SENT is terminal in StateStore.
                // No parent lock is acquired after a child lock, avoiding inversion with delivery/replay.
                "WITH candidates AS MATERIALIZED (SELECT d.id FROM notification_dead_letters d WHERE "+ELIGIBLE
                        +" ORDER BY GREATEST(d.resolved_at, d.created_at, d.updated_at), d.id LIMIT :limit FOR UPDATE OF d SKIP LOCKED) "
                        +"UPDATE notification_dead_letters d SET subject=NULL, message=NULL, failure_reason=NULL, "
                        +"content_compacted_at=:now, row_version=d.row_version+1 WHERE "+ELIGIBLE+" AND d.id IN (SELECT id FROM candidates)",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff)).addValue("now",Timestamp.from(compactedAt)).addValue("limit",batchSize))));
    }
    public Instant oldestEligible(Instant cutoff) {
        var rows=jdbc.query("SELECT GREATEST(d.resolved_at,d.created_at,d.updated_at) AS age FROM notification_dead_letters d WHERE "+ELIGIBLE
                        +" ORDER BY GREATEST(d.resolved_at,d.created_at,d.updated_at),d.id LIMIT 1",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff)),(row,index)->row.getTimestamp("age").toInstant());
        return rows.isEmpty()?null:rows.getFirst();
    }
}
