package org.demo.customerservice.audit.retention;

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
public class AuditRetentionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AuditRetentionRepository(DataSource datasource, PlatformTransactionManager manager) {
        var template = new JdbcTemplate(datasource);
        template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    public int deleteBatch(Instant cutoff, int batchSize) {
        Objects.requireNonNull(cutoff);
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid audit retention batch size");
        return Objects.requireNonNull(transaction.execute(status -> jdbc.update("""
                DELETE FROM audit_events e WHERE e.occurred_at < :cutoff AND e.id IN (
                    SELECT id FROM audit_events WHERE occurred_at < :cutoff
                    ORDER BY occurred_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                """, new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("limit", batchSize))));
    }

    /** The timestamp is sufficient to report age/backlog; no audit payload or full COUNT. */
    public Instant oldestEligible(Instant cutoff) {
        var rows = jdbc.query("SELECT occurred_at FROM audit_events WHERE occurred_at < :cutoff ORDER BY occurred_at, id LIMIT 1",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)),
                (row, index) -> row.getTimestamp("occurred_at").toInstant());
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
