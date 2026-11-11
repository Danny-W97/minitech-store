package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionSourceHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;

/** Explicit bounded probes. A committed pending probe is never automatically redispatched. */
public final class CommerceRetentionHoldReconciliationHistory {
    public enum Code { RECORDED, PENDING, SUPERSEDED, LIMIT_REACHED, UNKNOWN_REQUEST, NO_HISTORICAL_RECEIPT, QUARANTINED, CONFLICT, UNAVAILABLE, HISTORY }
    public record Outcome(CommerceRetentionHoldReconciliation.Code code,Instant observedAt,long bindingEpoch,long sourceRevision){}
    public record Probe(Code code,UUID probeId,Outcome outcome){
        public boolean durableHistoryRecorded(){return code==Code.RECORDED || code==Code.SUPERSEDED;}
        public boolean activationAllowed(){return false;}
        public boolean completeCaseCoverage(){return false;}
        public boolean repairPerformed(){return false;}
    }
    public record Entry(int number,UUID probeId,Instant createdAt,Outcome outcome,UUID supersededBy){
        public Entry(int number,UUID probeId,Instant createdAt,Outcome outcome){this(number,probeId,createdAt,outcome,null);}
        public boolean outcomeUnknown(){return outcome==null;}
        public boolean unresolvedPending(){return outcome==null && supersededBy==null;}
        public boolean requiresReview(){return outcome==null || outcome.code()!=CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ;}
    }
    public record History(Code code,List<Entry> entries){
        public History{entries=List.copyOf(entries);}
        public boolean historyOnly(){return true;}
        public boolean activationAllowed(){return false;}
        public boolean completeCaseCoverage(){return false;}
    }
    private record Scope(boolean found,boolean quarantined){}
    private record Work(Probe result){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionHoldReconciliation coordinator;
    public CommerceRetentionHoldReconciliationHistory(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);coordinator=new CommerceRetentionHoldReconciliation(ds,manager);}
    private static void valid(long order,UUID request,UUID ref,Participant source){
        if(order<1 || request==null || ref==null || source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source))throw new IllegalArgumentException("Invalid hold history scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_HISTORY_CALLER_TRANSACTION");
    }
    public Probe observe(long order,UUID request,UUID ref,Participant source,UUID probe,CommerceRetentionHoldReconciliation.Transport remote){
        valid(order,request,ref,source);if(probe==null || remote==null)throw new IllegalArgumentException("Invalid hold probe");
        final Work work;
        try{work=tx.execute(status->{
            var scope=scope(order,request,ref);if(!scope.found())return new Work(failed(Code.UNKNOWN_REQUEST,probe));
            if(scope.quarantined())return new Work(failed(Code.QUARANTINED,probe));
            var previous=jdbc.queryForList("SELECT request_id,source FROM commerce_retention_hold_probes WHERE probe_id=?",probe);
            if(!previous.isEmpty()){
                var row=previous.getFirst();if(!request.equals(row.get("request_id")) || !source.name().equals(row.get("source")))return new Work(failed(Code.CONFLICT,probe));
                return new Work(stored(probe));
            }
            var receipt=jdbc.queryForList("SELECT source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source=?",request,source.name());
            if(receipt.size()!=1)return new Work(failed(Code.NO_HISTORICAL_RECEIPT,probe));
            int count=jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probes WHERE request_id=? AND source=?",Integer.class,request,source.name());
            if(count>=8)return new Work(failed(Code.LIMIT_REACHED,probe));var r=receipt.getFirst();
            jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) VALUES(?,?,?,?,?,?)",request,source.name(),count+1,probe,r.get("source_request_id"),r.get("source_hold_id"));
            return new Work(null);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return failed(Code.UNAVAILABLE,probe);}
        if(work.result()!=null)return work.result();
        // The durable probe ID is the challenge. No Root transaction spans source RPC.
        var observed=coordinator.inspect(order,request,ref,source,probe,remote);
        try{return tx.execute(status->{
            var scope=scope(order,request,ref);if(!scope.found())return failed(Code.UNKNOWN_REQUEST,probe);
            var previous=stored(probe);if(previous.code()==Code.RECORDED || previous.code()==Code.SUPERSEDED)return previous;
            var code=scope.quarantined()?CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED:observed.code();
            Instant time=scope.quarantined()?null:observed.observedAt();long epoch=scope.quarantined()?0:observed.bindingEpoch(),revision=scope.quarantined()?0:observed.sourceRevision();
            jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,?,?,?,?)",probe,code.name(),time==null?null:Timestamp.from(time),epoch,revision);
            return stored(probe);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return failed(Code.UNAVAILABLE,probe);}
    }
    public History inspect(long order,UUID request,UUID ref,Participant source){
        valid(order,request,ref,source);
        try{return tx.execute(status->{
            var scope=scope(order,request,ref);if(!scope.found())return new History(Code.UNKNOWN_REQUEST,List.of());
            var entries=jdbc.query("SELECT p.probe_no,p.probe_id,p.created_at,r.code,r.observed_at,r.binding_epoch,r.source_revision,x.replacement_probe_id FROM commerce_retention_hold_probes p LEFT JOIN commerce_retention_hold_probe_results r USING(probe_id) LEFT JOIN commerce_retention_hold_probe_resolutions x USING(probe_id) WHERE p.request_id=? AND p.source=? ORDER BY p.probe_no LIMIT 8",(rs,n)->new Entry(rs.getInt("probe_no"),rs.getObject("probe_id",UUID.class),rs.getTimestamp("created_at").toInstant(),rs.getString("code")==null?null:new Outcome(CommerceRetentionHoldReconciliation.Code.valueOf(rs.getString("code")),rs.getTimestamp("observed_at")==null?null:rs.getTimestamp("observed_at").toInstant(),rs.getLong("binding_epoch"),rs.getLong("source_revision")),rs.getObject("replacement_probe_id",UUID.class)),request,source.name());
            return new History(scope.quarantined()?Code.QUARANTINED:Code.HISTORY,entries);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return new History(Code.UNAVAILABLE,List.of());}
    }
    private Scope scope(long order,UUID request,UUID ref){
        var rows=jdbc.queryForList("SELECT restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref);
        if(rows.isEmpty())return new Scope(false,false);
        boolean blocked=Boolean.TRUE.equals(rows.getFirst().get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
        return new Scope(true,blocked);
    }
    private Probe stored(UUID probe){
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_probe_resolutions WHERE probe_id=?)",Boolean.class,probe)))return failed(Code.SUPERSEDED,probe);
        var rows=jdbc.queryForList("SELECT code,observed_at,binding_epoch,source_revision FROM commerce_retention_hold_probe_results WHERE probe_id=?",probe);
        if(rows.isEmpty())return failed(Code.PENDING,probe);var r=rows.getFirst();
        return new Probe(Code.RECORDED,probe,new Outcome(CommerceRetentionHoldReconciliation.Code.valueOf((String)r.get("code")),r.get("observed_at")==null?null:((Timestamp)r.get("observed_at")).toInstant(),((Number)r.get("binding_epoch")).longValue(),((Number)r.get("source_revision")).longValue()));
    }
    private static Probe failed(Code code,UUID probe){return new Probe(code,probe,null);}
}
