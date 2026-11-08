package org.demo.orderservice.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationProgress.Inspection;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationReview.*;

/** Immutable diagnostic versions. A current local version is never an execution permit. */
public final class CommerceRetentionHoldReviewVersions {
    public static final String PROTOCOL="HOLD_RESTORATION_REVIEW_V1";
    public enum Code { CAPTURED, ALREADY_CAPTURED, CURRENT_LOCAL, UNKNOWN_REQUEST, UNKNOWN_REVIEW, CONFLICT,
        QUARANTINED, LIMIT_REACHED, EXPIRED, CLOCK_CHANGED, VERSION_MISMATCH, SCOPE_CHANGED, HISTORY_CHANGED,
        RESULT_CHANGED, INCONSISTENT_HISTORY, UNAVAILABLE }
    public record Snapshot(UUID reviewId,int ordinal,String protocol,String scopeHash,String historyHash,String resultHash,String versionHash,Instant reviewedAt,Instant expiresAt){}
    public record Result(Code code,Snapshot snapshot,Review review){
        public boolean diagnosticOnly(){return true;}
        public boolean currentAuthority(){return false;}
        public boolean completeCaseCoverage(){return false;}
        public boolean repairExecutionAllowed(){return false;}
        public boolean activationAllowed(){return false;}
        public boolean executionTerminated(){return false;}
    }
    record Frame(String scope,String history,String result,Review review){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionHoldRestorationReview reviewer;
    public CommerceRetentionHoldReviewVersions(DataSource ds,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(15);reviewer=new CommerceRetentionHoldRestorationReview(ds,manager);
    }
    public Result capture(long order,UUID request,UUID ref,UUID reviewId){
        valid(order,request,ref,reviewId);
        try{return tx.execute(status->{
            if(!parent(order,request,ref))return result(Code.UNKNOWN_REQUEST,null,null);
            if(quarantined(order,request))return result(Code.QUARANTINED,null,null);
            var old=jdbc.queryForList("SELECT * FROM commerce_retention_hold_review_versions WHERE review_id=?",reviewId);
            if(!old.isEmpty()){
                if(!request.equals(old.getFirst().get("request_id")))return result(Code.CONFLICT,null,null);
                var s=read(old.getFirst());return integrity(s)?result(Code.ALREADY_CAPTURED,s,null):result(Code.INCONSISTENT_HISTORY,null,null);
            }
            int n=jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_versions WHERE request_id=?",Integer.class,request);
            if(n>=8)return result(Code.LIMIT_REACHED,null,null);
            var f=frame(order,request,ref);if(f==null)return result(Code.INCONSISTENT_HISTORY,null,null);
            if(f.review().code()==CommerceRetentionHoldRestorationReview.Code.QUARANTINED || quarantined(order,request))return result(Code.QUARANTINED,null,null);
            Instant now=now(),expires=expiry(f.review(),now);
            if(!expires.isAfter(now))return result(Code.EXPIRED,null,null);
            String version=version(f.scope(),f.history(),f.result());
            jdbc.update("INSERT INTO commerce_retention_hold_review_versions(review_id,request_id,ordinal,protocol,scope_hash,history_hash,result_hash,version_hash,reviewed_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",reviewId,request,n+1,PROTOCOL,f.scope(),f.history(),f.result(),version,Timestamp.from(now),Timestamp.from(expires));
            return result(Code.CAPTURED,new Snapshot(reviewId,n+1,PROTOCOL,f.scope(),f.history(),f.result(),version,now,expires),f.review());
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,null,null);}
        catch(IllegalArgumentException inconsistent){return result(Code.INCONSISTENT_HISTORY,null,null);}
    }
    public Result check(long order,UUID request,UUID ref,UUID reviewId,String expectedVersion){
        valid(order,request,ref,reviewId);if(expectedVersion==null || !expectedVersion.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid expected review version");
        try{return tx.execute(status->checkLocked(order,request,ref,reviewId,expectedVersion));}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,null,null);}
        catch(IllegalArgumentException inconsistent){return result(Code.INCONSISTENT_HISTORY,null,null);}
    }
    Result checkLocked(long order,UUID request,UUID ref,UUID reviewId,String expectedVersion){
            requireTransaction();
            if(!parent(order,request,ref))return result(Code.UNKNOWN_REQUEST,null,null);
            var rows=jdbc.queryForList("SELECT * FROM commerce_retention_hold_review_versions WHERE review_id=? AND request_id=?",reviewId,request);
            if(rows.isEmpty())return result(Code.UNKNOWN_REVIEW,null,null);var s=read(rows.getFirst());
            if(quarantined(order,request))return result(Code.QUARANTINED,s,null);
            if(!integrity(s))return result(Code.INCONSISTENT_HISTORY,null,null);
            if(!expectedVersion.equals(s.versionHash()))return result(Code.VERSION_MISMATCH,s,null);
            Code clock=clock(s,now());if(clock!=null)return result(clock,s,null);
            var f=frame(order,request,ref);if(f==null)return result(Code.INCONSISTENT_HISTORY,s,null);
            if(f.review().code()==CommerceRetentionHoldRestorationReview.Code.QUARANTINED || quarantined(order,request))return result(Code.QUARANTINED,s,null);
            // Recheck the lease after all bounded reads; a slow check cannot extend it.
            clock=clock(s,now());if(clock!=null)return result(clock,s,null);
            if(!s.scopeHash().equals(f.scope()))return result(Code.SCOPE_CHANGED,s,f.review());
            if(!s.historyHash().equals(f.history()))return result(Code.HISTORY_CHANGED,s,f.review());
            if(!s.resultHash().equals(f.result()))return result(Code.RESULT_CHANGED,s,f.review());
            return result(Code.CURRENT_LOCAL,s,f.review());
        
    }
    Frame frame(long order,UUID request,UUID ref){
        requireTransaction();
        var review=reviewer.snapshot(order,request,ref);
        if(review.code()!=CommerceRetentionHoldRestorationReview.Code.REVIEWED && review.code()!=CommerceRetentionHoldRestorationReview.Code.QUARANTINED)return null;
        var scope=new ArrayList<Object>();
        scope.add(jdbc.queryForList("SELECT r.request_id,r.order_id,r.case_reference,r.guard,r.reason_code,r.root_hold_id,r.restore_blocked,r.created_at,o.customer_id,o.version FROM commerce_retention_hold_requests r JOIN orders o ON o.id=r.order_id WHERE r.request_id=?",request));
        scope.add(jdbc.queryForList("SELECT scope_type,subject_id,revision,restore_blocked FROM commerce_retention_hold_scopes WHERE (scope_type='ORDER' AND subject_id=?) OR (scope_type='CUSTOMER' AND subject_id=(SELECT customer_id FROM orders WHERE id=?)) ORDER BY scope_type,subject_id LIMIT 2",order,order));
        scope.add(jdbc.queryForList("SELECT order_id,process_revision,open_seen,reconciled_at,restore_blocked FROM commerce_retention_order_state WHERE order_id=?",order));
        scope.add(jdbc.queryForList("SELECT customer_id,request_id,state FROM account_privacy_fences WHERE customer_id=(SELECT customer_id FROM orders WHERE id=?)",order));
        scope.add(jdbc.queryForList("SELECT p.source,p.source_request_id,p.created_at,d.source_request_id AS receipt_request,d.source_hold_id,d.attempt_id,d.recorded_at FROM commerce_retention_hold_source_requests p LEFT JOIN commerce_retention_hold_delivery_receipts d ON d.request_id=p.request_id AND d.source=p.source WHERE p.request_id=? ORDER BY p.source LIMIT 8",request));
        var history=new ArrayList<Object>();
        history.add(jdbc.queryForList("SELECT source,attempt_no,attempt_id,created_at FROM commerce_retention_hold_delivery_attempts WHERE request_id=? ORDER BY source,attempt_no LIMIT 57",request));
        history.add(jdbc.queryForList("SELECT p.source,p.probe_no,p.probe_id,p.source_request_id,p.source_hold_id,p.created_at,r.code,r.observed_at,r.binding_epoch,r.source_revision,r.recorded_at,x.resolution_id,x.replacement_probe_id,x.created_at AS resolution_created FROM commerce_retention_hold_probes p LEFT JOIN commerce_retention_hold_probe_results r USING(probe_id) LEFT JOIN commerce_retention_hold_probe_resolutions x USING(probe_id) WHERE p.request_id=? ORDER BY p.source,p.probe_no LIMIT 57",request));
        var intents=jdbc.queryForList("SELECT i.intent_id,i.request_id,i.source,i.basis_probe_id,i.source_request_id,i.source_hold_id,i.created_at,s.stop_id,s.created_at AS stopped_at FROM commerce_retention_hold_probes p JOIN LATERAL (SELECT i.* FROM commerce_retention_hold_repair_intents i WHERE i.basis_probe_id=p.probe_id LIMIT 1) i ON TRUE LEFT JOIN commerce_retention_hold_restoration_stops s USING(intent_id) WHERE p.request_id=? ORDER BY p.source,p.probe_no LIMIT 57",request);
        history.add(intents);
        for(var i:intents)history.add(jdbc.queryForList("SELECT a.attempt_id,a.attempt_no,a.mode,a.scope_hash,a.case_authorization_id,a.created_at,r.code,r.observed_at,r.binding_epoch,r.source_revision,r.recorded_at FROM commerce_retention_hold_restoration_attempts a LEFT JOIN commerce_retention_hold_restoration_results r USING(attempt_id) WHERE a.intent_id=? ORDER BY a.attempt_no LIMIT 9",i.get("intent_id")));
        // reviewedAt is a reading clock, not evidence: expiry is stored separately.
        var stable=new Review(review.code(),null,review.sources(),review.totals());
        return new Frame(HoldReviewFingerprint.hash(PROTOCOL+"/scope",scope),HoldReviewFingerprint.hash(PROTOCOL+"/history",history),HoldReviewFingerprint.hash(PROTOCOL+"/result",stable),review);
    }
    static Instant expiry(Review review,Instant issued){
        Instant expires=issued.plusSeconds(15);
        for(var source:review.sources())for(var intent:source.intents()){
            var p=intent.progress();
            if(Set.of(Inspection.MATCH_AS_OF_READ,Inspection.MISSING_AT_SOURCE,Inspection.CONFLICT_REVIEW,Inspection.UNKNOWN_PURCHASE,Inspection.SOURCE_QUARANTINED,Inspection.VERSION_CHANGED).contains(p.inspection()) && p.observedAt()!=null && p.observedAt().plusSeconds(15).isBefore(expires))expires=p.observedAt().plusSeconds(15);
        }
        return expires;
    }
    static Code clock(Snapshot s,Instant now){return now.isBefore(s.reviewedAt())?Code.CLOCK_CHANGED:!now.isBefore(s.expiresAt())?Code.EXPIRED:null;}
    private static String version(String scope,String history,String result){return HoldReviewFingerprint.hash(PROTOCOL+"/version",scope,history,result);}
    private static boolean integrity(Snapshot s){return PROTOCOL.equals(s.protocol()) && version(s.scopeHash(),s.historyHash(),s.resultHash()).equals(s.versionHash()) && s.expiresAt().isAfter(s.reviewedAt()) && !s.expiresAt().isAfter(s.reviewedAt().plusSeconds(15));}
    private boolean parent(long order,UUID request,UUID ref){return !jdbc.queryForList("SELECT request_id FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref).isEmpty();}
    private boolean quarantined(long order,UUID request){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_requests WHERE request_id=? AND restore_blocked) OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,request,order,order,order));}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static Snapshot read(Map<String,Object> r){return new Snapshot((UUID)r.get("review_id"),((Number)r.get("ordinal")).intValue(),(String)r.get("protocol"),(String)r.get("scope_hash"),(String)r.get("history_hash"),(String)r.get("result_hash"),(String)r.get("version_hash"),((Timestamp)r.get("reviewed_at")).toInstant(),((Timestamp)r.get("expires_at")).toInstant());}
    private static void valid(long order,UUID request,UUID ref,UUID review){if(order<1 || request==null || ref==null || review==null)throw new IllegalArgumentException("Invalid versioned review scope");if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_REVIEW_VERSION_CALLER_TRANSACTION");}
    private static Result result(Code code,Snapshot snapshot,Review review){return new Result(code,snapshot,review);}
    private static void requireTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("HOLD_REVIEW_VERSION_REQUIRES_TRANSACTION");}
}
