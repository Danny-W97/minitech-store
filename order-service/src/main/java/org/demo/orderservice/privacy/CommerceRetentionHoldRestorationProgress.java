package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;

/** Two independent diagnostic dimensions: durable delivery history and an as-of-source read.
 * A match never resolves an unknown write, proves executor termination or grants clearance. */
public final class CommerceRetentionHoldRestorationProgress {
    public enum Code { ASSESSED, STOPPED, QUARANTINED, UNKNOWN_INTENT, INCONSISTENT_HISTORY, UNAVAILABLE }
    public enum Delivery { NOT_ATTEMPTED, OUTCOME_UNKNOWN, HISTORICAL_ACKNOWLEDGEMENT, DIAGNOSTIC_ONLY }
    public enum Inspection { REQUIRED, PENDING, OLDER_THAN_REPAIR, STALE, MATCH_AS_OF_READ, MISSING_AT_SOURCE,
        CONFLICT_REVIEW, UNKNOWN_PURCHASE, SOURCE_QUARANTINED, VERSION_CHANGED, UNAVAILABLE, BLOCKED }
    public record Progress(Code code,Delivery delivery,int attemptsUsed,int pendingAttempts,int historicalAcknowledgements,
                           int probesUsed,int remainingProbes,int pendingProbes,int supersededProbes,
                           Inspection inspection,UUID latestProbeId,Instant observedAt,long bindingEpoch,long sourceRevision){
        public boolean currentAuthority(){return false;}
        public boolean executionTerminated(){return false;}
        public boolean repairExecutionAllowed(){return false;}
        public boolean completeCaseCoverage(){return false;}
        public boolean activationAllowed(){return false;}
    }
    public record Refresh(CommerceRetentionHoldReconciliationHistory.Probe probe,Progress progress){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final CommerceRetentionHoldReconciliationHistory history;
    public CommerceRetentionHoldRestorationProgress(DataSource ds,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
        history=new CommerceRetentionHoldReconciliationHistory(ds,manager);
    }
    public Refresh refresh(long order,UUID request,UUID ref,Participant source,UUID intent,UUID probe,CommerceRetentionHoldReconciliation.Transport remote){
        valid(order,request,ref,source,intent);if(probe==null || remote==null)throw new IllegalArgumentException("Invalid restoration progress probe");
        var before=assess(order,request,ref,source,intent);
        if(before.code()!=Code.ASSESSED)return new Refresh(null,before);
        // Existing probe protocol commits before RPC, never redispatches an existing ID,
        // and checks quarantine again. No transaction from this adapter spans the call.
        var observed=history.observe(order,request,ref,source,probe,remote);
        return new Refresh(observed,assess(order,request,ref,source,intent));
    }
    public Progress assess(long order,UUID request,UUID ref,Participant source,UUID intent){
        valid(order,request,ref,source,intent);
        try{return tx.execute(status->snapshot(order,request,ref,source,intent));}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return failed(Code.UNAVAILABLE);}
        catch(IllegalArgumentException invalidHistory){return failed(Code.INCONSISTENT_HISTORY);}
    }
    // Package-local reuse by the seven-source review with the same data source and
    // transaction manager. Public entry points still reject caller transactions.
    Progress snapshot(long order,UUID request,UUID ref,Participant source,UUID intent){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RESTORATION_PROGRESS_SNAPSHOT_REQUIRES_TRANSACTION");
        var parents=jdbc.queryForList("SELECT guard,reason_code,restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref);
        if(parents.isEmpty())return failed(Code.UNKNOWN_INTENT);
        var intents=jdbc.queryForList("SELECT i.basis_probe_id,i.source_request_id,i.source_hold_id,i.created_at,p.probe_no,b.binding_epoch,b.source_revision FROM commerce_retention_hold_repair_intents i JOIN commerce_retention_hold_probes p ON p.probe_id=i.basis_probe_id AND p.request_id=i.request_id AND p.source=i.source AND p.source_request_id=i.source_request_id AND p.source_hold_id=i.source_hold_id JOIN commerce_retention_hold_probe_results b ON b.probe_id=p.probe_id AND b.code='MISSING_AT_SOURCE' JOIN commerce_retention_hold_delivery_receipts d ON d.request_id=i.request_id AND d.source=i.source AND d.source_request_id=i.source_request_id AND d.source_hold_id=i.source_hold_id WHERE i.intent_id=? AND i.request_id=? AND i.source=?",intent,request,source.name());
        if(intents.size()!=1)return failed(Code.UNKNOWN_INTENT);var basis=intents.getFirst();
        boolean quarantined=Boolean.TRUE.equals(parents.getFirst().get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
        boolean stopped=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_stops WHERE intent_id=?)",Boolean.class,intent));
        var attempts=jdbc.queryForList("SELECT a.attempt_no,a.mode,a.scope_hash,a.case_authorization_id,a.created_at,r.code,r.observed_at,r.recorded_at,r.binding_epoch,r.source_revision FROM commerce_retention_hold_restoration_attempts a LEFT JOIN commerce_retention_hold_restoration_results r USING(attempt_id) WHERE a.intent_id=? ORDER BY a.attempt_no LIMIT 9",intent);
        if(attempts.size()>8)return failed(Code.INCONSISTENT_HISTORY);
        Instant boundary=time(basis,"created_at");long expectedEpoch=number(basis,"binding_epoch"),minRevision=number(basis,"source_revision");
        var parent=parents.getFirst();
        String expectedHash=SourceHoldRestorationProtocol.scopeHash(new SourceHoldRestorationProtocol.Command(
            new SourceHoldDeliveryProtocol.Command(source,order,(UUID)basis.get("source_request_id"),CommerceRetentionAssessment.Guard.valueOf((String)parent.get("guard")),CommerceRetentionSourceHolds.Reason.valueOf((String)parent.get("reason_code"))),
            intent,ref,(UUID)basis.get("source_hold_id"),expectedEpoch,minRevision,intent,"",SourceHoldRestorationProtocol.Mode.RESTORE));
        int pending=0,ack=0;String scope=null;UUID authorization=null;
        for(int n=0;n<attempts.size();n++){
            var a=attempts.get(n);
            if(number(a,"attempt_no")!=n+1 || !(n==0?"RESTORE":"REPLAY_ONLY").equals(a.get("mode")))return failed(Code.INCONSISTENT_HISTORY);
            if(n==0){scope=(String)a.get("scope_hash");authorization=(UUID)a.get("case_authorization_id");}
            if(!expectedHash.equals(scope) || authorization==null || !scope.equals(a.get("scope_hash")) || !authorization.equals(a.get("case_authorization_id")))return failed(Code.INCONSISTENT_HISTORY);
            boundary=later(boundary,time(a,"created_at"));
            if(a.get("code")==null){pending++;continue;}
            boundary=later(boundary,time(a,"recorded_at"));
            if(Set.of("RESTORED","REPLAYED").contains(a.get("code"))){
                if(number(a,"binding_epoch")!=expectedEpoch || number(a,"source_revision")<=number(basis,"source_revision") || time(a,"observed_at")==null || ("RESTORED".equals(a.get("code")) && n!=0))return failed(Code.INCONSISTENT_HISTORY);
                ack++;minRevision=Math.max(minRevision,number(a,"source_revision"));
            }
        }
        var probes=jdbc.queryForList("SELECT p.probe_id,p.probe_no,p.created_at,p.source_request_id,p.source_hold_id,r.code,r.observed_at,r.binding_epoch,r.source_revision,x.replacement_probe_id FROM commerce_retention_hold_probes p LEFT JOIN commerce_retention_hold_probe_results r USING(probe_id) LEFT JOIN commerce_retention_hold_probe_resolutions x USING(probe_id) WHERE p.request_id=? AND p.source=? ORDER BY p.probe_no LIMIT 9",request,source.name());
        if(probes.size()>8)return failed(Code.INCONSISTENT_HISTORY);
        int pendingProbes=0,superseded=0;
        for(int n=0;n<probes.size();n++){
            var p=probes.get(n);
            if(number(p,"probe_no")!=n+1 || !basis.get("source_request_id").equals(p.get("source_request_id")) || !basis.get("source_hold_id").equals(p.get("source_hold_id")))return failed(Code.INCONSISTENT_HISTORY);
            if(p.get("code")==null){if(p.get("replacement_probe_id")==null)pendingProbes++;else superseded++;}
            else if(p.get("replacement_probe_id")!=null)return failed(Code.INCONSISTENT_HISTORY);
        }
        for(int n=0;n<probes.size();n++)if(probes.get(n).get("replacement_probe_id")!=null){
            UUID replacement=(UUID)probes.get(n).get("replacement_probe_id");boolean found=false;
            for(int m=n+1;m<probes.size();m++)if(replacement.equals(probes.get(m).get("probe_id")) && ("MATCH_AS_OF_READ".equals(probes.get(m).get("code")) || "MISSING_AT_SOURCE".equals(probes.get(m).get("code"))))found=true;
            if(!found)return failed(Code.INCONSISTENT_HISTORY);
        }
        Delivery delivery=ack>0?Delivery.HISTORICAL_ACKNOWLEDGEMENT:pending>0?Delivery.OUTCOME_UNKNOWN:attempts.isEmpty()?Delivery.NOT_ATTEMPTED:Delivery.DIAGNOSTIC_ONLY;
        Map<String,Object> latest=probes.isEmpty()?null:probes.getLast();Inspection inspection=Inspection.REQUIRED;
        UUID latestId=latest==null?null:(UUID)latest.get("probe_id");Instant observed=latest==null?null:time(latest,"observed_at");
        long epoch=latest==null || latest.get("binding_epoch")==null?0:number(latest,"binding_epoch"),revision=latest==null || latest.get("source_revision")==null?0:number(latest,"source_revision");
        if(quarantined || stopped)inspection=Inspection.BLOCKED;
        else if(latest!=null){
            if(latest.get("code")==null)inspection=Inspection.PENDING;
            else if(number(latest,"probe_no")<=number(basis,"probe_no") || time(latest,"created_at").isBefore(boundary))inspection=Inspection.OLDER_THAN_REPAIR;
            else if(observed==null)inspection=Inspection.UNAVAILABLE;
            else if(observed.isBefore(Instant.now().minusSeconds(15)) || observed.isAfter(Instant.now().plusSeconds(5)))inspection=Inspection.STALE;
            else inspection=switch((String)latest.get("code")){
                case "SOURCE_UNKNOWN"->Inspection.UNKNOWN_PURCHASE;
                case "SOURCE_QUARANTINED"->Inspection.SOURCE_QUARANTINED;
                case "SOURCE_CONFLICT"->Inspection.CONFLICT_REVIEW;
                case "MATCH_AS_OF_READ","MISSING_AT_SOURCE"->epoch!=expectedEpoch || revision<minRevision?Inspection.VERSION_CHANGED:"MATCH_AS_OF_READ".equals(latest.get("code"))?Inspection.MATCH_AS_OF_READ:Inspection.MISSING_AT_SOURCE;
                default->Inspection.UNAVAILABLE;
            };
        }
        return new Progress(quarantined?Code.QUARANTINED:stopped?Code.STOPPED:Code.ASSESSED,delivery,attempts.size(),pending,ack,probes.size(),8-probes.size(),pendingProbes,superseded,inspection,latestId,observed,epoch,revision);
    }
    private static Instant time(Map<String,Object> row,String key){return row.get(key)==null?null:((Timestamp)row.get(key)).toInstant();}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Instant later(Instant a,Instant b){return b!=null && b.isAfter(a)?b:a;}
    private static void valid(long order,UUID request,UUID ref,Participant source,UUID intent){
        if(order<1 || request==null || ref==null || source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source) || intent==null)throw new IllegalArgumentException("Invalid restoration progress scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_RESTORATION_PROGRESS_CALLER_TRANSACTION");
    }
    private static Progress failed(Code code){return new Progress(code,null,0,0,0,0,0,0,0,Inspection.UNAVAILABLE,null,null,0,0);}
}
