package org.demo.notificationservice.messaging.retention;

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
public class RejectedInboxRetentionRepository {
    private static final String ELIGIBLE = """
            status = 'REJECTED' AND related_id IS NULL AND diagnostics_compacted_at IS NULL
            AND (payload IS NOT NULL OR error IS NOT NULL) AND received_at < :cutoff
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public RejectedInboxRetentionRepository(DataSource datasource, PlatformTransactionManager manager) {
        var template = new JdbcTemplate(datasource);template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);
    }
    public int compactBatch(Instant cutoff, Instant compactedAt, int batchSize) {
        Objects.requireNonNull(cutoff);Objects.requireNonNull(compactedAt);
        if (batchSize < 1 || batchSize > 1000 || compactedAt.isBefore(cutoff))
            throw new IllegalArgumentException("Invalid rejected inbox compaction batch");
        return Objects.requireNonNull(transaction.execute(status -> jdbc.update(
                // Materialize once so an UPDATE rescan cannot expand the locking candidate batch.
                "WITH candidates AS MATERIALIZED (SELECT event_id FROM event_inbox WHERE " + ELIGIBLE
                        + " ORDER BY received_at, event_id LIMIT :limit FOR UPDATE SKIP LOCKED) "
                        + "UPDATE event_inbox SET payload = NULL, error = NULL, diagnostics_compacted_at = :now WHERE " + ELIGIBLE
                        + " AND event_id IN (SELECT event_id FROM candidates)",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff))
                        .addValue("now",Timestamp.from(compactedAt)).addValue("limit",batchSize))));
    }
    public Instant oldestEligible(Instant cutoff) {
        var rows=jdbc.query("SELECT received_at FROM event_inbox WHERE " + ELIGIBLE + " ORDER BY received_at, event_id LIMIT 1",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff)),(row,index)->row.getTimestamp("received_at").toInstant());
        return rows.isEmpty()?null:rows.getFirst();
    }
}
