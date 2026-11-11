package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Source-local binding CAS and durable negative notifications; never a positive proof. */
public final class CommerceRetentionSourceOutbox {
    public record Binding(Subject subject, Phase phase, long epoch, long revision, boolean invalidated) {}
    public record Work(Subject subject, Phase phase, long epoch, long revision, UUID token) {
        public Work {
            Objects.requireNonNull(subject); Objects.requireNonNull(phase); Objects.requireNonNull(token);
            if (epoch < 1 || revision < 1) throw new IllegalArgumentException("Invalid source work");
        }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CommerceRetentionSourceOutbox(DataSource source, PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(2);
        tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }

    /** Expected epoch is immutable across retries; never fetch a new epoch for an old command. */
    public Binding bind(Subject subject, Phase phase, long expectedEpoch) {
        Objects.requireNonNull(subject); Objects.requireNonNull(phase);
        if (expectedEpoch < 0 || expectedEpoch == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid source epoch");
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))", (rs,n) -> true, subject.orderId());
            var rows = jdbc.queryForList("SELECT * FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE", subject.orderId());
            if (rows.isEmpty()) {
                if (expectedEpoch != 0) throw new IllegalStateException("RETENTION_BINDING_CONFLICT");
                jdbc.update("INSERT INTO commerce_retention_source_state(order_id,binding_epoch,generation,order_version,phase) VALUES(?,1,?,?,?)",
                        subject.orderId(), subject.chainGeneration(), subject.orderVersion(), phase.name());
            } else {
                var previous = rows.getFirst();
                if (Boolean.TRUE.equals(previous.get("restore_blocked")))
                    throw new IllegalStateException("RETENTION_SOURCE_QUARANTINED");
                if (subject.chainGeneration().equals(previous.get("generation"))) {
                    if (subject.orderVersion() != number(previous,"order_version") || !phase.name().equals(previous.get("phase")))
                        throw new IllegalStateException("RETENTION_BINDING_CONFLICT");
                    return binding(previous); // Includes a dirty/restored binding: no implicit clearance.
                }
                if (number(previous,"binding_epoch") != expectedEpoch || subject.orderVersion() < number(previous,"order_version"))
                    throw new IllegalStateException("RETENTION_BINDING_CONFLICT");
                jdbc.update("UPDATE commerce_retention_source_state SET binding_epoch=binding_epoch+1,generation=?,order_version=?,phase=?,invalidated=FALSE,updated_at=CURRENT_TIMESTAMP WHERE order_id=?",
                        subject.chainGeneration(), subject.orderVersion(), phase.name(), subject.orderId());
            }
            return binding(jdbc.queryForMap("SELECT * FROM commerce_retention_source_state WHERE order_id=?", subject.orderId()));
        });
    }

    public Optional<Work> claim() {
        return tx.execute(status -> {
            jdbc.update("""
                    UPDATE commerce_retention_change_outbox SET state='FAILED',code='RETRY_LIMIT',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
                    WHERE (order_id,binding_epoch) IN (SELECT order_id,binding_epoch FROM commerce_retention_change_outbox o
                    WHERE state='LEASED' AND attempts=8 AND run_until<=clock_timestamp()
                    AND EXISTS(SELECT 1 FROM commerce_retention_source_state s WHERE s.order_id=o.order_id AND NOT s.restore_blocked)
                    ORDER BY retry_at,order_id LIMIT 25 FOR UPDATE SKIP LOCKED)
                    """);
            var rows = jdbc.queryForList("""
                    SELECT * FROM commerce_retention_change_outbox o WHERE state IN ('PENDING','LEASED','FAILED') AND attempts<8
                    AND retry_at<=clock_timestamp() AND (run_until IS NULL OR run_until<=clock_timestamp())
                    AND EXISTS(SELECT 1 FROM commerce_retention_source_state s WHERE s.order_id=o.order_id AND NOT s.restore_blocked)
                    ORDER BY retry_at,order_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """);
            if (rows.isEmpty()) return Optional.empty();
            var row = rows.getFirst(); UUID token = UUID.randomUUID();
            jdbc.update("UPDATE commerce_retention_change_outbox SET state='LEASED',attempts=attempts+1,run_token=?,run_until=clock_timestamp()+INTERVAL '15 seconds',updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND binding_epoch=?",
                    token, row.get("order_id"), row.get("binding_epoch"));
            return Optional.of(new Work(subject(row), Phase.valueOf((String)row.get("phase")), number(row,"binding_epoch"), number(row,"source_revision"), token));
        });
    }

    /** An acknowledgement never clears source invalidation or any hold. */
    public boolean acknowledge(Work work) { return finish(work, true); }
    public boolean failed(Work work) { return finish(work, false); }
    private boolean finish(Work work, boolean sent) {
        Objects.requireNonNull(work);
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.update("""
                UPDATE commerce_retention_change_outbox o SET state=?,sent_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                code=CASE WHEN ? THEN NULL WHEN attempts=8 THEN 'RETRY_LIMIT' ELSE 'TRANSPORT_FAILED' END,
                run_token=NULL,run_until=NULL,retry_at=clock_timestamp()+INTERVAL '30 seconds',updated_at=CURRENT_TIMESTAMP
                WHERE order_id=? AND binding_epoch=? AND generation=? AND order_version=? AND phase=? AND source_revision=?
                AND state='LEASED' AND run_token=? AND run_until>clock_timestamp()
                AND EXISTS(SELECT 1 FROM commerce_retention_source_state s WHERE s.order_id=o.order_id AND NOT s.restore_blocked)
                """, sent ? "SENT" : "FAILED", sent, sent, work.subject().orderId(), work.epoch(), work.subject().chainGeneration(),
                work.subject().orderVersion(), work.phase().name(), work.revision(), work.token()) == 1));
    }

    /** One durable item, HTTP after the claim commits, bounded metadata acknowledgement. */
    public boolean publishOne(CommerceRetentionInvalidationClient client) {
        Objects.requireNonNull(client);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("RETENTION_INVALIDATION_CALLER_TRANSACTION");
        var next = claim(); if (next.isEmpty()) return false;
        var work = next.get();
        try { client.invalidate(work.subject(),work.phase()); }
        catch (RuntimeException failure) { failed(work); return true; }
        acknowledge(work); return true;
    }

    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static Subject subject(Map<String,Object> row) {
        // Source outboxes deliberately contain no customer identity. The wire command omits it.
        return new Subject(number(row,"order_id"),0,number(row,"order_version"),(UUID)row.get("generation"));
    }
    private static Binding binding(Map<String,Object> row) {
        return new Binding(subject(row),Phase.valueOf((String)row.get("phase")),number(row,"binding_epoch"),
                number(row,"source_revision"),Boolean.TRUE.equals(row.get("invalidated")));
    }
}
