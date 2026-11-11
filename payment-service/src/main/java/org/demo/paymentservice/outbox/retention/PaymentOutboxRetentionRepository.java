package org.demo.paymentservice.outbox.retention;

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
public class PaymentOutboxRetentionRepository {
    private static final String ELIGIBLE = """
            status = 'PUBLISHED' AND published_at IS NOT NULL AND recipient IS NOT NULL
            AND payload_compacted_at IS NULL AND processing_token IS NULL AND processing_until IS NULL
            AND GREATEST(published_at, created_at) < :cutoff
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PaymentOutboxRetentionRepository(DataSource datasource, PlatformTransactionManager manager) {
        var template = new JdbcTemplate(datasource);template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);
    }
    public int compactBatch(Instant cutoff, Instant compactedAt, int batchSize) {
        Objects.requireNonNull(cutoff);Objects.requireNonNull(compactedAt);
        if (batchSize < 1 || batchSize > 1000 || compactedAt.isBefore(cutoff))
            throw new IllegalArgumentException("Invalid payment outbox compaction batch");
        return Objects.requireNonNull(transaction.execute(status -> jdbc.update(
                // Materialize once: a rescan of a locking IN subquery can otherwise exceed the batch limit.
                "WITH candidates AS MATERIALIZED (SELECT id FROM payment_outbox_events WHERE " + ELIGIBLE
                        + " ORDER BY GREATEST(published_at, created_at), id LIMIT :limit FOR UPDATE SKIP LOCKED) "
                        + "UPDATE payment_outbox_events SET recipient = NULL, delivery_address = NULL, payload_compacted_at = :now WHERE " + ELIGIBLE
                        + " AND id IN (SELECT id FROM candidates)",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff))
                        .addValue("now",Timestamp.from(compactedAt)).addValue("limit",batchSize))));
    }
    public Instant oldestEligible(Instant cutoff) {
        var rows=jdbc.query("SELECT GREATEST(published_at, created_at) AS age FROM payment_outbox_events WHERE "
                        + ELIGIBLE + " ORDER BY GREATEST(published_at, created_at), id LIMIT 1",
                new MapSqlParameterSource("cutoff",Timestamp.from(cutoff)),(row,index)->row.getTimestamp("age").toInstant());
        return rows.isEmpty()?null:rows.getFirst();
    }
}
