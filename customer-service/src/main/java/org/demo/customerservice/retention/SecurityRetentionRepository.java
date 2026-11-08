package org.demo.customerservice.retention;

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
import java.util.List;
import java.util.Objects;

@Repository
public class SecurityRetentionRepository {
    /** Fixed SQL vocabulary, never table/column names supplied by an API caller. */
    public enum Store {
        REFRESH("refresh_tokens", "GREATEST(t.expires_at, t.revoked_at, t.rotated_at)", """
                GREATEST(t.expires_at, t.revoked_at, t.rotated_at) < :cutoff AND NOT EXISTS (
                    SELECT 1 FROM refresh_tokens family
                    WHERE family.customer_id = t.customer_id AND family.family_id = t.family_id
                    AND GREATEST(family.expires_at, family.revoked_at, family.rotated_at) >= :cutoff
                )
                """),
        PASSWORD_RESET("password_reset_tokens", "GREATEST(t.expires_at, t.used_at)",
                "GREATEST(t.expires_at, t.used_at) < :cutoff"),
        RECOVERY_USED("customer_recovery_codes", "t.used_at", "t.used_at < :cutoff"),
        TOTP_INACTIVE("customer_totp_methods", "COALESCE(t.enrollment_expires_at, t.updated_at)",
                "t.enabled = FALSE AND COALESCE(t.enrollment_expires_at, t.updated_at) < :cutoff"),
        VERIFICATION_EXPIRED("customers", "t.verification_expires_at",
                "t.enabled = FALSE AND t.verification_token_hash IS NOT NULL AND t.verification_expires_at < :cutoff",
                "verification_token_hash = NULL"),
        VERIFICATION_CONFIRMED("customers",
                "COALESCE(GREATEST(t.verification_expires_at, t.verification_last_sent_at, t.verification_resend_window_started_at), t.updated_at)",
                """
                t.enabled = TRUE AND (t.verification_token_hash IS NOT NULL OR t.verification_expires_at IS NOT NULL
                    OR t.verification_last_sent_at IS NOT NULL OR t.verification_resend_window_started_at IS NOT NULL
                    OR t.verification_resend_count <> 0)
                AND COALESCE(GREATEST(t.verification_expires_at, t.verification_last_sent_at,
                    t.verification_resend_window_started_at), t.updated_at) < :cutoff
                """,
                "verification_token_hash = NULL, verification_expires_at = NULL, verification_resend_count = 0, "
                        + "verification_last_sent_at = NULL, verification_resend_window_started_at = NULL");

        private final String table, age, eligible;
        private final String assignments;
        Store(String table, String age, String eligible) {this(table, age, eligible, null);}
        Store(String table, String age, String eligible, String assignments) {
            this.table = table; this.age = age; this.eligible = eligible; this.assignments = assignments;
        }
        public String metricTag() {return name().toLowerCase(java.util.Locale.ROOT);}
        public boolean clearsFields() {return assignments != null;}
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SecurityRetentionRepository(DataSource datasource, PlatformTransactionManager manager) {
        var template = new JdbcTemplate(datasource);
        template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    public int cleanupBatch(Store store, Instant cutoff, int batchSize) {
        Objects.requireNonNull(store); Objects.requireNonNull(cutoff);
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid security retention batch size");
        return Objects.requireNonNull(transaction.execute(status -> {
            var parameters = new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("limit", batchSize);
            String owner = store.clearsFields() ? "t.id" : "t.customer_id";
            List<Long> owners = jdbc.queryForList("SELECT c.id FROM " + store.table
                    + " t JOIN customers c ON c.id = " + owner + " WHERE " + store.eligible
                    + " ORDER BY " + store.age + ", t.id LIMIT :limit FOR UPDATE OF c SKIP LOCKED",
                    parameters, Long.class).stream().distinct().toList();
            if (owners.isEmpty()) return 0;
            parameters.addValue("owners", owners);
            // A separate statement after the owner locks gets a fresh READ COMMITTED snapshot.
            // MFA enrollment and refresh use the same customer-first locking order.
            String mutation = store.clearsFields() ? "UPDATE " + store.table + " t SET " + store.assignments
                    : "DELETE FROM " + store.table + " t";
            return jdbc.update(mutation + " WHERE " + store.eligible + " AND t.id IN ("
                    + "SELECT t.id FROM " + store.table + " t WHERE " + owner + " IN (:owners) AND " + store.eligible
                    + " ORDER BY " + store.age + ", t.id LIMIT :limit FOR UPDATE SKIP LOCKED)", parameters);
        }));
    }

    /** Oldest eligible record, including one deferred by an owner/row lock. No full COUNT. */
    public Instant oldestEligible(Store store, Instant cutoff) {
        var rows = jdbc.query("SELECT " + store.age + " AS age FROM " + store.table + " t WHERE " + store.eligible
                        + " ORDER BY " + store.age + ", t.id LIMIT 1",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)),
                (row, index) -> row.getTimestamp("age").toInstant());
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
