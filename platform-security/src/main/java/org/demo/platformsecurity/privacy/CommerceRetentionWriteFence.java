package org.demo.platformsecurity.privacy;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.*;

/** Source-local SQL lease, shared by replicas and enforced by database write triggers.
 * A lease protects source observations, never authorizes a business mutation. */
public final class CommerceRetentionWriteFence {
    private final Participant own;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CommerceRetentionWriteFence(Participant own, DataSource ds, PlatformTransactionManager manager) {
        if (own == null || own == Participant.ORDER) throw new IllegalArgumentException("Invalid fence source");
        this.own = own;
        jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(2);
        tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }

    public Reply apply(Command c) {
        if (c == null || !c.valid() || c.scope().source() != own) throw new IllegalArgumentException("Invalid fence scope");
        return tx.execute(status -> {
            var s = c.scope();
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))", (rs,n) -> true, s.orderId());
            var rows = jdbc.queryForList("SELECT * FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE", s.orderId());
            if (rows.isEmpty()) return reply(c, Status.STALE, null, null);
            var row = rows.getFirst();
            if (!s.generation().equals(row.get("generation")) || s.orderVersion()!=number(row,"order_version")
                    || !s.phase().name().equals(row.get("phase")) || s.epoch()!=number(row,"binding_epoch")
                    || s.revision()!=number(row,"source_revision")) return reply(c, Status.STALE, null, null);
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
            var token = row.get("retention_fence_token");
            var until = row.get("retention_fence_until") instanceof Timestamp time ? time.toInstant() : null;
            boolean live = token != null && until != null && until.isAfter(now);
            if (Boolean.TRUE.equals(row.get("restore_blocked"))) return reply(c, Status.BLOCKED, null, null);
            var history = jdbc.queryForList("SELECT released FROM commerce_retention_fence_tokens WHERE order_id=? AND binding_epoch=? AND fence_token=?",
                    s.orderId(),s.epoch(),s.fenceToken());
            boolean known = !history.isEmpty();
            boolean released = known && Boolean.TRUE.equals(history.getFirst().get("released"));
            if (c.action() == Action.RELEASE) {
                // Burn the token even if RELEASE arrived before PREPARE. Commit with the lease removal.
                if (!known) {
                    if (!capacity(s)) return reply(c, Status.BLOCKED, null, null);
                    remember(s,true);
                } else if (!released) {
                    jdbc.update("UPDATE commerce_retention_fence_tokens SET released=TRUE WHERE order_id=? AND binding_epoch=? AND fence_token=?",
                            s.orderId(),s.epoch(),s.fenceToken());
                }
                if (Objects.equals(token, s.fenceToken())) {
                    jdbc.update("UPDATE commerce_retention_source_state SET retention_fence_token=NULL,retention_fence_until=NULL WHERE order_id=?", s.orderId());
                }
                return reply(c, Status.RELEASED, null, null);
            }
            if (released) return reply(c, Status.STALE, null, null);
            if (Boolean.TRUE.equals(row.get("invalidated"))) return reply(c, Status.STALE, null, null);
            String ready = own == Participant.INVENTORY ? "inventory_retention_ready" : "commerce_retention_peer_ready";
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + ready + "(?)", Boolean.class, s.orderId())))
                return reply(c, Status.BLOCKED, null, null);
            if (c.action() == Action.VERIFY && (!known || !live || !Objects.equals(token,s.fenceToken())))
                return reply(c, Status.STALE, null, null);
            // An issued token cannot acquire a lease again after expiry or replacement.
            if (known && (!live || !Objects.equals(token,s.fenceToken()))) return reply(c, Status.STALE, null, null);
            if (!live && Objects.equals(token,s.fenceToken())) return reply(c, Status.STALE, null, null);
            if (live && !Objects.equals(token,s.fenceToken())) return reply(c, Status.CONFLICT, null, null);
            if (!known) {
                if (!capacity(s)) return reply(c, Status.BLOCKED, null, null);
                remember(s,false);
            }
            if (!live) {
                // A repeated PREPARE does not extend an existing lease.
                var times = jdbc.queryForMap("""
                    UPDATE commerce_retention_source_state
                    SET retention_fence_token=?,retention_fence_until=clock_timestamp()+INTERVAL '15 seconds'
                    WHERE order_id=? RETURNING retention_fence_until,clock_timestamp() AS observed_at
                    """, s.fenceToken(), s.orderId());
                until = ((Timestamp)times.get("retention_fence_until")).toInstant();
                now = ((Timestamp)times.get("observed_at")).toInstant();
            }
            return reply(c, Status.FENCED, now, until);
        });
    }

    private boolean capacity(Scope s) {
        return jdbc.queryForObject("SELECT count(*) FROM commerce_retention_fence_tokens WHERE order_id=? AND binding_epoch=?",Integer.class,s.orderId(),s.epoch()) < 8;
    }
    private void remember(Scope s,boolean released) {
        jdbc.update("INSERT INTO commerce_retention_fence_tokens(order_id,binding_epoch,fence_token,released) VALUES(?,?,?,?)",
                s.orderId(),s.epoch(),s.fenceToken(),released);
    }

    private static long number(java.util.Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
}
