package org.demo.orderservice.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Trusted in-process D02 purchase blockers, not a complete hold inventory.
 * No public mutation endpoint, release, expiry, free text or positive clearance.
 * CUSTOMER means this account's purchases, not erasure of its Customer profile. */
public final class CommerceRetentionHolds {
    public enum Reason { PRESERVATION_REQUEST, OPEN_CASE, DEPENDENCY_REVIEW }
    private static final Set<Guard> KINDS=Set.of(Guard.NO_PRESERVATION_HOLD,Guard.NO_OPEN_OR_REOPENED_CASE,Guard.NO_ACTIVE_RETRY_OR_DLQ,
        Guard.FINANCIAL_DEPENDENCIES_RELEASED,Guard.RESTORE_DEPENDENCIES_RELEASED,Guard.RETRY_DEPENDENCIES_RELEASED,Guard.DEDUP_DEPENDENCIES_RELEASED);
    public record Snapshot(Set<Guard> blockers,long orderRevision,long customerRevision,boolean quarantined){public Snapshot{blockers=Set.copyOf(blockers);}}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionHolds(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    public UUID placeOrder(long order,UUID request,Guard guard,Reason reason){return place("ORDER",order,request,guard,reason);}
    public UUID placeCustomerPurchases(long customer,UUID request,Guard guard,Reason reason){return place("CUSTOMER",customer,request,guard,reason);}
    private UUID place(String type,long id,UUID request,Guard guard,Reason reason){
        if(id<1 || request==null || guard==null || !KINDS.contains(guard) || reason==null)throw new IllegalArgumentException("Invalid purchase hold");
        return tx.execute(status->{
            // Order-first for order holds. Customer holds never lock or scan orders.
            if(type.equals("ORDER")){if(jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR NO KEY UPDATE",id).isEmpty())throw new IllegalArgumentException("Unknown retention order");}
            else if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM orders WHERE customer_id=?)",Boolean.class,id)))throw new IllegalArgumentException("Unknown retention customer");
            ensure(type,id);jdbc.queryForList("SELECT revision FROM commerce_retention_hold_scopes WHERE scope_type=? AND subject_id=? FOR UPDATE",type,id);
            UUID candidate=UUID.randomUUID();int inserted=jdbc.update("INSERT INTO commerce_retention_holds(hold_id,scope_type,subject_id,guard,reason_code,request_id) VALUES(?,?,?,?,?,?) ON CONFLICT(request_id) DO NOTHING",candidate,type,id,guard.name(),reason.name(),request);
            var row=jdbc.queryForMap("SELECT hold_id,scope_type,subject_id,guard,reason_code FROM commerce_retention_holds WHERE request_id=?",request);
            if(!type.equals(row.get("scope_type")) || id!=((Number)row.get("subject_id")).longValue() || !guard.name().equals(row.get("guard")) || !reason.name().equals(row.get("reason_code")))throw new IllegalStateException("RETENTION_HOLD_REQUEST_CONFLICT");
            if(type.equals("ORDER") && inserted==1)jdbc.update("UPDATE commerce_retention_jobs SET current=FALSE,state='STALE',code='SOURCE_INVALIDATED',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND current",id);
            return (UUID)row.get("hold_id");
        });
    }
    /** Caller holds the Order row. Scope locks remain held through assessment commit. */
    Snapshot snapshot(long order,long customer,Phase phase){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_SNAPSHOT_TRANSACTION_REQUIRED");
        long customerRevision=0;boolean quarantined=false;
        if(customer>0){var c=locked("CUSTOMER",customer);customerRevision=((Number)c.get("revision")).longValue();quarantined=Boolean.TRUE.equals(c.get("restore_blocked"));}
        var o=locked("ORDER",order);quarantined|=Boolean.TRUE.equals(o.get("restore_blocked"));var blocks=EnumSet.noneOf(Guard.class);
        for(var guard:requiredGuards(phase))if(KINDS.contains(guard) && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_holds WHERE guard=? AND ((scope_type='ORDER' AND subject_id=?) OR (scope_type='CUSTOMER' AND subject_id=?)))",Boolean.class,guard.name(),order,customer)))blocks.add(guard);
        return new Snapshot(blocks,((Number)o.get("revision")).longValue(),customerRevision,quarantined);
    }
    private Map<String,Object> locked(String type,long id){ensure(type,id);return jdbc.queryForMap("SELECT revision,restore_blocked FROM commerce_retention_hold_scopes WHERE scope_type=? AND subject_id=? FOR SHARE",type,id);}
    private void ensure(String type,long id){jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id) VALUES(?,?) ON CONFLICT DO NOTHING",type,id);}
}
