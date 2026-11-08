package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionSourceHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;

/** Trusted, bounded local inspection. Every receipt is historical; no source RPC,
 * retry, ownership proof, current coverage or mutation authority is implied. */
public final class CommerceRetentionHoldDeliveryProgress {
    public enum Code { OBSERVED, QUARANTINED, UNKNOWN_REQUEST, UNAVAILABLE }
    public enum State { NOT_ATTEMPTED, OUTCOME_UNKNOWN, REVIEW_REQUIRED, HISTORICAL_RECEIPT }
    public record Source(Participant source,UUID sourceRequestId,int attempts,int remainingAttempts,
                         State state,UUID sourceHoldId){}
    public record Progress(Code code,List<Source> sources) {
        public Progress { sources=List.copyOf(sources); }
        public boolean historyOnly(){return true;}
        public boolean currentSourceReconciliationRequired(){return true;}
        public boolean completeCaseCoverage(){return false;}
        public boolean activationAllowed(){return false;}
    }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionHoldDeliveryProgress(DataSource ds,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }
    public Progress inspect(long order,UUID request,UUID caseReference){
        if(order<1 || request==null || caseReference==null)throw new IllegalArgumentException("Invalid hold progress scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_PROGRESS_CALLER_TRANSACTION");
        try{return tx.execute(status->{
            // All delivery writers use this parent lock. The seven counters and receipts
            // describe one local snapshot, including while an RPC is still in flight.
            var parent=jdbc.queryForList("SELECT restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,caseReference);
            if(parent.isEmpty())return new Progress(Code.UNKNOWN_REQUEST,List.of());
            boolean quarantined=Boolean.TRUE.equals(parent.getFirst().get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
            var rows=jdbc.queryForList("""
                SELECT p.source,p.source_request_id,
                  (SELECT count(*) FROM commerce_retention_hold_delivery_attempts a WHERE a.request_id=p.request_id AND a.source=p.source) AS attempts,
                  r.source_hold_id
                FROM commerce_retention_hold_source_requests p
                LEFT JOIN commerce_retention_hold_delivery_receipts r ON r.request_id=p.request_id AND r.source=p.source
                WHERE p.request_id=? ORDER BY p.source
                """,request);
            if(rows.size()!=7)return new Progress(Code.UNAVAILABLE,List.of());
            var seen=EnumSet.noneOf(Participant.class);var sources=new ArrayList<Source>(7);
            for(var row:rows){
                Participant source;
                try{source=Participant.valueOf((String)row.get("source"));}catch(IllegalArgumentException invalid){return new Progress(Code.UNAVAILABLE,List.of());}
                int attempts=((Number)row.get("attempts")).intValue();UUID sourceRequest=(UUID)row.get("source_request_id"),hold=(UUID)row.get("source_hold_id");
                if(!CommerceRetentionSourceHolds.SOURCES.contains(source) || !seen.add(source) || sourceRequest==null || attempts<0 || attempts>8 || (hold!=null && attempts==0))return new Progress(Code.UNAVAILABLE,List.of());
                State state=hold!=null?State.HISTORICAL_RECEIPT:attempts==0?State.NOT_ATTEMPTED:attempts==8?State.REVIEW_REQUIRED:State.OUTCOME_UNKNOWN;
                sources.add(new Source(source,sourceRequest,attempts,8-attempts,state,hold));
            }
            return new Progress(quarantined?Code.QUARANTINED:Code.OBSERVED,sources);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return new Progress(Code.UNAVAILABLE,List.of());}
    }
}
