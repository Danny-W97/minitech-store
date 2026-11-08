package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionSourceHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;

/** One explicitly requested read. A durable reservation is never a redispatch instruction. */
public final class CommerceRetentionVersionedHoldInspection {
    public enum Code { RECORDED, PENDING, SUPERSEDED, REJECTED, UNKNOWN_REQUEST, CONFLICT,
        QUARANTINED, STOPPED, LIMIT_REACHED, EXPIRED, CLOCK_CHANGED, DATA_CHANGED, UNAVAILABLE }
    public record Result(Code code,UUID stepId,CommerceRetentionHoldReviewVersions.Code reviewCode,
                         CommerceRetentionHoldReconciliationHistory.Outcome outcome){
        public boolean diagnosticOnly(){return true;}
        public boolean currentAuthority(){return false;}
        public boolean completeCaseCoverage(){return false;}
        public boolean repairExecutionAllowed(){return false;}
        public boolean activationAllowed(){return false;}
        public boolean executionTerminated(){return false;}
    }
    private record Work(Result prior){}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommerceRetentionHoldReviewVersions versions;
    private final CommerceRetentionHoldReconciliation coordinator;

    public CommerceRetentionVersionedHoldInspection(DataSource ds,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(15);
        versions=new CommerceRetentionHoldReviewVersions(ds,manager);coordinator=new CommerceRetentionHoldReconciliation(ds,manager);
    }
    public Result inspect(long order,UUID request,UUID ref,UUID review,String expectedVersion,
                          Participant source,UUID intent,UUID step,CommerceRetentionHoldReconciliation.Transport remote){
        valid(order,request,ref,review,expectedVersion,source,intent,step);
        if(remote==null)throw new IllegalArgumentException("Missing inspection transport");
        try{
            Work work=tx.execute(status->{
                if(!parent(order,request,ref))return new Work(result(Code.UNKNOWN_REQUEST,step));
                var old=jdbc.queryForList("SELECT s.*,v.version_hash FROM commerce_retention_hold_review_steps s JOIN commerce_retention_hold_review_versions v USING(review_id) WHERE s.step_id=?",step);
                if(!old.isEmpty()){
                    var r=old.getFirst();
                    if(!review.equals(r.get("review_id")) || !intent.equals(r.get("intent_id")) || !source.name().equals(r.get("source")) || !expectedVersion.equals(r.get("version_hash"))
                        || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_review_versions WHERE review_id=? AND request_id=?)",Boolean.class,review,request)))return new Work(result(Code.CONFLICT,step));
                    // Retrying is history only, even if the lease is expired or a previous commit was unknown.
                    return new Work(stored(step));
                }
                var checked=versions.checkLocked(order,request,ref,review,expectedVersion);
                if(checked.code()!=CommerceRetentionHoldReviewVersions.Code.CURRENT_LOCAL)return new Work(new Result(Code.REJECTED,step,checked.code(),null));
                var target=jdbc.queryForList("SELECT i.* FROM commerce_retention_hold_probes p JOIN LATERAL (SELECT i.* FROM commerce_retention_hold_repair_intents i WHERE i.basis_probe_id=p.probe_id LIMIT 1) i ON TRUE WHERE p.request_id=? AND p.source=? AND i.intent_id=? LIMIT 1",request,source.name(),intent);
                if(target.size()!=1)return new Work(result(Code.CONFLICT,step));
                if(stopped(intent))return new Work(result(Code.STOPPED,step));
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_review_steps WHERE review_id=? AND source=?) OR EXISTS(SELECT 1 FROM commerce_retention_hold_probes WHERE probe_id=?)",Boolean.class,review,source.name(),step)))return new Work(result(Code.CONFLICT,step));
                int n=jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probes WHERE request_id=? AND source=?",Integer.class,request,source.name());
                if(n>=8)return new Work(result(Code.LIMIT_REACHED,step));
                var t=target.getFirst();
                jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) VALUES(?,?,?,?,?,?)",request,source.name(),n+1,step,t.get("source_request_id"),t.get("source_hold_id"));
                // Our pending probe changes history. Bind exactly this change without renewing the original lease.
                var prepared=versions.frame(order,request,ref);
                if(prepared==null || !checked.snapshot().scopeHash().equals(prepared.scope())){status.setRollbackOnly();return new Work(result(Code.DATA_CHANGED,step));}
                jdbc.update("INSERT INTO commerce_retention_hold_review_steps(step_id,review_id,intent_id,source,probe_id,prepared_scope_hash,prepared_history_hash,prepared_result_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",step,review,intent,source.name(),step,prepared.scope(),prepared.history(),prepared.result(),Timestamp.from(checked.snapshot().expiresAt()));
                return new Work(null);
            });
            if(work.prior()!=null)return work.prior();
            // Reservation COMMIT is acknowledged before any source call. No SQL transaction spans HTTP.
            var observed=coordinator.inspect(order,request,ref,source,step,c->{
                Code gate=tx.execute(status->guard(order,request,ref,intent,step));
                if(gate!=null)throw new IllegalStateException("VERSIONED_INSPECTION_CONTEXT_CHANGED");
                return remote.inspect(c);
            });
            return tx.execute(status->{
                if(!parent(order,request,ref))return result(Code.UNKNOWN_REQUEST,step);
                var previous=stored(step);if(previous.code()!=Code.PENDING)return previous;
                Code gate=guard(order,request,ref,intent,step);
                var code=gate==Code.QUARANTINED?CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED:gate==null?observed.code():CommerceRetentionHoldReconciliation.Code.UNAVAILABLE;
                jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,?,?,?,?)",step,code.name(),gate==null && observed.observedAt()!=null?Timestamp.from(observed.observedAt()):null,gate==null?observed.bindingEpoch():0,gate==null?observed.sourceRevision():0);
                return gate==null?stored(step):result(gate,step);
            });
        }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|IllegalArgumentException unavailable){return result(Code.UNAVAILABLE,step);}
    }
    private Code guard(long order,UUID request,UUID ref,UUID intent,UUID step){
        if(!parent(order,request,ref))return Code.UNKNOWN_REQUEST;
        if(quarantined(order,request))return Code.QUARANTINED;
        if(stopped(intent))return Code.STOPPED;
        if(stored(step).code()!=Code.PENDING)return Code.DATA_CHANGED;
        var row=jdbc.queryForMap("SELECT s.*,v.reviewed_at FROM commerce_retention_hold_review_steps s JOIN commerce_retention_hold_review_versions v USING(review_id) WHERE s.step_id=?",step);
        Instant now=now();
        if(now.isBefore(((Timestamp)row.get("reviewed_at")).toInstant()) || now.isBefore(((Timestamp)row.get("created_at")).toInstant()))return Code.CLOCK_CHANGED;
        if(!now.isBefore(((Timestamp)row.get("expires_at")).toInstant()))return Code.EXPIRED;
        var frame=versions.frame(order,request,ref);
        if(frame==null || !row.get("prepared_scope_hash").equals(frame.scope()) || !row.get("prepared_history_hash").equals(frame.history()) || !row.get("prepared_result_hash").equals(frame.result()))return Code.DATA_CHANGED;
        now=now();
        if(now.isBefore(((Timestamp)row.get("created_at")).toInstant()))return Code.CLOCK_CHANGED;
        return !now.isBefore(((Timestamp)row.get("expires_at")).toInstant())?Code.EXPIRED:null;
    }
    private Result stored(UUID step){
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_probe_resolutions WHERE probe_id=?)",Boolean.class,step)))return result(Code.SUPERSEDED,step);
        var rows=jdbc.queryForList("SELECT code,observed_at,binding_epoch,source_revision FROM commerce_retention_hold_probe_results WHERE probe_id=?",step);
        if(rows.isEmpty())return result(Code.PENDING,step);
        var r=rows.getFirst();return new Result(Code.RECORDED,step,null,new CommerceRetentionHoldReconciliationHistory.Outcome(CommerceRetentionHoldReconciliation.Code.valueOf((String)r.get("code")),r.get("observed_at")==null?null:((Timestamp)r.get("observed_at")).toInstant(),((Number)r.get("binding_epoch")).longValue(),((Number)r.get("source_revision")).longValue()));
    }
    private boolean parent(long order,UUID request,UUID ref){return !jdbc.queryForList("SELECT request_id FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref).isEmpty();}
    private boolean quarantined(long order,UUID request){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_requests WHERE request_id=? AND restore_blocked) OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,request,order,order,order));}
    private boolean stopped(UUID intent){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_stops WHERE intent_id=?)",Boolean.class,intent));}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static Result result(Code code,UUID step){return new Result(code,step,null,null);}
    private static void valid(long order,UUID request,UUID ref,UUID review,String version,Participant source,UUID intent,UUID step){
        if(order<1 || request==null || ref==null || review==null || version==null || !version.matches("[0-9a-f]{64}") || !CommerceRetentionSourceHolds.SOURCES.contains(source) || intent==null || step==null)throw new IllegalArgumentException("Invalid versioned inspection scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("VERSIONED_HOLD_INSPECTION_CALLER_TRANSACTION");
    }
}
