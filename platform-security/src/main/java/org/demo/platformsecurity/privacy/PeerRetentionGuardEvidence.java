package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.*;
import org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.Code;

/** Reads canonical source columns only. Missing/history/foreign copies never imply clearance. */
public final class PeerRetentionGuardEvidence {
    private static final String TYPES="'ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED'";
    private final Participant own;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PeerRetentionGuardEvidence(Participant own,DataSource ds,PlatformTransactionManager manager){
        if(!SOURCES.contains(own))throw new IllegalArgumentException("Unsupported guard source");
        this.own=own;jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }
    public Reply collect(Command c){
        if(c==null || !c.valid() || c.scope().source()!=own)throw new IllegalArgumentException("Invalid local guard scope");
        return tx.execute(status->{
            var s=c.scope();jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,s.orderId());
            var rows=jdbc.queryForList("SELECT generation,order_version,phase,binding_epoch,source_revision,restore_blocked,invalidated FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE",s.orderId());
            Instant time=jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
            if(rows.isEmpty())return reply(c,Code.STALE,time,unknown());var r=rows.getFirst();
            if(Boolean.TRUE.equals(r.get("restore_blocked")))return reply(c,Code.QUARANTINED,time,unknown());
            if(!s.generation().equals(r.get("generation")) || s.orderVersion()!=n(r,"order_version") || !s.phase().name().equals(r.get("phase")) || s.epoch()!=n(r,"binding_epoch") || s.revision()!=n(r,"source_revision") || Boolean.TRUE.equals(r.get("invalidated")))return reply(c,Code.STALE,time,unknown());
            var states=new EnumMap<Guard,State>(Guard.class);states.putAll(unknown());
            long order=s.orderId();boolean retry=switch(own){
                case RETURNS->{
                    if(exists("SELECT EXISTS(SELECT 1 FROM return_requests WHERE order_id=? AND status<>'REJECTED')",order))states.put(Guard.NO_OPEN_OR_REOPENED_CASE,State.BLOCKED);
                    yield false; // A16 effects/history do not establish retry/DLQ clearance.
                }
                case PAYMENT->exists("SELECT EXISTS(SELECT 1 FROM payments WHERE order_id=? AND (status NOT IN ('SUCCEEDED','FAILED') OR processing_token IS NOT NULL OR processing_until IS NOT NULL OR stripe_external_hold OR stripe_compensation_required))",order)
                    || exists("SELECT EXISTS(SELECT 1 FROM payment_follow_ups WHERE order_id=? AND (status<>'SUCCEEDED' OR processing_token IS NOT NULL OR processing_until IS NOT NULL))",order)
                    || exists("SELECT EXISTS(SELECT 1 FROM payment_outbox_events WHERE order_id=? AND (status<>'PUBLISHED' OR published_at IS NULL OR processing_token IS NOT NULL OR processing_until IS NOT NULL))",order);
                case BILLING->exists("SELECT EXISTS(SELECT 1 FROM invoice_outbox_events WHERE order_id=? AND (status<>'PUBLISHED' OR published_at IS NULL OR processing_token IS NOT NULL OR processing_until IS NOT NULL))",order)
                    || exists("SELECT EXISTS(SELECT 1 FROM event_inbox WHERE order_id=? AND status<>'ACCEPTED')",order);
                case NOTIFICATION->exists("SELECT EXISTS(SELECT 1 FROM notifications WHERE source_id=? AND event_type IN ("+TYPES+") AND (status<>'SENT' OR processing_token IS NOT NULL OR processing_until IS NOT NULL OR next_attempt_at IS NOT NULL))",order)
                    || exists("SELECT EXISTS(SELECT 1 FROM notification_dead_letters d WHERE d.source_id=? AND d.event_type IN ("+TYPES+") AND (d.status<>'RESOLVED' OR d.resolved_at IS NULL))",order)
                    || exists("SELECT EXISTS(SELECT 1 FROM event_inbox WHERE order_id=? AND status<>'ACCEPTED')",order);
                case DELIVERY->exists("SELECT EXISTS(SELECT 1 FROM shipments WHERE order_id=? AND (status NOT IN ('DELIVERED','PICKED_UP','CANCELLED') OR sync_state<>'SYNCED' OR sync_token IS NOT NULL OR sync_until IS NOT NULL OR sync_next_attempt_at IS NOT NULL))",order);
                case PROMOTION->exists("SELECT EXISTS(SELECT 1 FROM coupon_reservations WHERE order_id=? AND state NOT IN ('CONSUMED','RELEASED'))",order);
                case INVENTORY->exists("SELECT EXISTS(SELECT 1 FROM inventory_reservations WHERE order_id=? AND status NOT IN ('CONFIRMED','RELEASED'))",order);
                default->throw new IllegalStateException("Unsupported guard source");
            };
            if(retry)states.put(Guard.NO_ACTIVE_RETRY_OR_DLQ,State.BLOCKED);
            for(String guard:jdbc.queryForList("SELECT DISTINCT guard FROM commerce_retention_source_holds WHERE order_id=?",String.class,order))
                states.put(Guard.valueOf(guard),State.BLOCKED);
            return reply(c,Code.OBSERVED,time,states);
        });
    }
    private boolean exists(String sql,long order){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,order));}
    private static long n(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
}
