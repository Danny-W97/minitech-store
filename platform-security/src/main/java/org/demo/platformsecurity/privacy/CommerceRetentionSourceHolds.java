package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Trusted in-process registration of known source-local purchase blockers.
 * Seven source-local schemas use the same reviewed ledger. No public API,
 * customer matching, release, TTL, complete inventory or positive clearance. */
public final class CommerceRetentionSourceHolds {
    public enum Reason { PRESERVATION_REQUEST, OPEN_CASE, DEPENDENCY_REVIEW }
    public static final Set<Guard> GUARDS=Set.of(Guard.NO_PRESERVATION_HOLD,Guard.NO_OPEN_OR_REOPENED_CASE,Guard.NO_ACTIVE_RETRY_OR_DLQ);
    public static final Set<Participant> SOURCES=PeerRetentionGuardProtocol.SOURCES;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionSourceHolds(Participant source,DataSource ds,PlatformTransactionManager manager){
        if(source==null || !SOURCES.contains(source))throw new IllegalArgumentException("Unsupported source hold registry");
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }
    public UUID place(long order,UUID request,Guard guard,Reason reason){
        if(order<1 || request==null || guard==null || !GUARDS.contains(guard) || reason==null)throw new IllegalArgumentException("Invalid source hold");
        return tx.execute(status->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,order);
            if(jdbc.queryForList("SELECT order_id FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE",order).isEmpty())
                throw new IllegalArgumentException("Unknown source purchase");
            jdbc.update("INSERT INTO commerce_retention_source_holds(hold_id,order_id,guard,reason_code,request_id) VALUES(?,?,?,?,?) ON CONFLICT(request_id) DO NOTHING",
                UUID.randomUUID(),order,guard.name(),reason.name(),request);
            var row=jdbc.queryForMap("SELECT hold_id,order_id,guard,reason_code FROM commerce_retention_source_holds WHERE request_id=?",request);
            if(order!=((Number)row.get("order_id")).longValue() || !guard.name().equals(row.get("guard")) || !reason.name().equals(row.get("reason_code")))
                throw new IllegalStateException("RETENTION_SOURCE_HOLD_REQUEST_CONFLICT");
            return (UUID)row.get("hold_id");
        });
    }
}
