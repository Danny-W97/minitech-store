package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionSourceHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Trusted local persistence only: an opaque case reference is not proof of ownership
 * or a decision. No operator API, evidence files, source RPC, release or global clearance. */
public final class CommerceRetentionHoldIntake {
    public enum Code { REGISTERED, IDEMPOTENT, QUARANTINED }
    public record Registration(Code code,UUID requestId,long orderId,UUID caseReference,UUID rootHoldId,Map<Participant,UUID> sourceRequests){
        public Registration { sourceRequests=Map.copyOf(sourceRequests); }
        public boolean remoteDeliveryConfirmed(){return false;}
        public boolean activationAllowed(){return false;}
    }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionHolds holds;
    public CommerceRetentionHoldIntake(DataSource ds,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);holds=new CommerceRetentionHolds(ds,manager);
    }
    public Registration register(long order,UUID request,UUID caseReference,Guard guard,CommerceRetentionHolds.Reason reason){
        scope(order,request,caseReference);
        if(guard==null || !CommerceRetentionSourceHolds.GUARDS.contains(guard) || reason==null)throw new IllegalArgumentException("Invalid hold intake");
        outsideTransaction();
        return tx.execute(status->{
            if(jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR NO KEY UPDATE",order).isEmpty())throw new IllegalArgumentException("Unknown intake purchase");
            var existing=jdbc.queryForList("SELECT * FROM commerce_retention_hold_requests WHERE request_id=? FOR UPDATE",request);
            if(!existing.isEmpty()){
                same(existing.getFirst(),order,caseReference,guard,reason);
                return registration(existing.getFirst(),Code.IDEMPOTENT);
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order)))
                throw new IllegalStateException("RETENTION_HOLD_INTAKE_QUARANTINED");
            // Separate persisted root/source identifiers avoid borrowing an unrelated legacy hold request.
            UUID rootHold=holds.placeOrder(order,UUID.randomUUID(),guard,reason);
            int inserted=jdbc.update("INSERT INTO commerce_retention_hold_requests(request_id,order_id,case_reference,guard,reason_code,root_hold_id) VALUES(?,?,?,?,?,?) ON CONFLICT(request_id) DO NOTHING",
                request,order,caseReference,guard.name(),reason.name(),rootHold);
            if(inserted!=1)throw new IllegalStateException("RETENTION_HOLD_INTAKE_CONFLICT");
            for(var source:CommerceRetentionSourceHolds.SOURCES)
                jdbc.update("INSERT INTO commerce_retention_hold_source_requests(request_id,source,source_request_id) VALUES(?,?,?)",request,source.name(),UUID.randomUUID());
            return registration(jdbc.queryForMap("SELECT * FROM commerce_retention_hold_requests WHERE request_id=?",request),Code.REGISTERED);
        });
    }
    public Optional<Registration> inspect(long order,UUID request,UUID caseReference){
        scope(order,request,caseReference);outsideTransaction();
        return tx.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM commerce_retention_hold_requests WHERE request_id=? AND order_id=? AND case_reference=? FOR SHARE",request,order,caseReference);
            return rows.isEmpty()?Optional.empty():Optional.of(registration(rows.getFirst(),Code.IDEMPOTENT));
        });
    }
    private Registration registration(Map<String,Object> row,Code code){
        UUID id=(UUID)row.get("request_id");var plan=new EnumMap<Participant,UUID>(Participant.class);
        for(var source:jdbc.queryForList("SELECT source,source_request_id FROM commerce_retention_hold_source_requests WHERE request_id=?",id))
            plan.put(Participant.valueOf((String)source.get("source")),(UUID)source.get("source_request_id"));
        if(!plan.keySet().equals(CommerceRetentionSourceHolds.SOURCES))throw new IllegalStateException("RETENTION_HOLD_INTAKE_INCOMPLETE");
        return new Registration(Boolean.TRUE.equals(row.get("restore_blocked"))?Code.QUARANTINED:code,id,((Number)row.get("order_id")).longValue(),
            (UUID)row.get("case_reference"),(UUID)row.get("root_hold_id"),plan);
    }
    private static void same(Map<String,Object> row,long order,UUID caseRef,Guard guard,CommerceRetentionHolds.Reason reason){
        if(((Number)row.get("order_id")).longValue()!=order || !caseRef.equals(row.get("case_reference")) || !guard.name().equals(row.get("guard")) || !reason.name().equals(row.get("reason_code")))
            throw new IllegalStateException("RETENTION_HOLD_INTAKE_CONFLICT");
    }
    private static void scope(long order,UUID request,UUID caseReference){if(order<1 || request==null || caseReference==null)throw new IllegalArgumentException("Invalid hold intake scope");}
    private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_INTAKE_CALLER_TRANSACTION");}
}
