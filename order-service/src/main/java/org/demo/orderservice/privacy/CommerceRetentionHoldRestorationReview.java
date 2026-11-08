package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionSourceHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationProgress.*;

/** Bounded, local seven-source history review. No remote refresh, write or clearance. */
public final class CommerceRetentionHoldRestorationReview {
    public enum Code { REVIEWED, QUARANTINED, UNKNOWN_REQUEST, INCONSISTENT_HISTORY, UNAVAILABLE }
    public enum Reason { NO_HISTORICAL_RECEIPT, NO_REPAIR_INTENT, NOT_ATTEMPTED, UNKNOWN_WRITE,
        DIAGNOSTIC_WRITE_RESULT, INSPECTION_REQUIRED, INSPECTION_PENDING, STALE_INSPECTION,
        SOURCE_DISCREPANCY, SOURCE_VERSION_CHANGED, STOPPED, ROOT_QUARANTINED,
        PROBE_BUDGET_EXHAUSTED, READ_PENDING, SUPERSEDED_READ }
    public record Intent(UUID intentId,UUID basisProbeId,int basisProbeNumber,boolean stopped,Progress progress){}
    public record Source(Participant source,UUID sourceRequestId,UUID sourceHoldId,int probesUsed,int remainingProbes,
                         int pendingProbes,int supersededProbes,UUID latestProbeId,List<Intent> intents,Set<Reason> reasons){
        public Source{intents=List.copyOf(intents);reasons=Set.copyOf(reasons);}
        public boolean reviewRequired(){return !reasons.isEmpty();}
    }
    public record Totals(int sources,int intents,int attempts,int pendingWrites,int historicalAcknowledgements,
                         int probes,int pendingReads,int supersededReads,int stoppedIntents,int sourcesWithFreshMatches,int reviewSources){}
    public record Review(Code code,Instant reviewedAt,List<Source> sources,Totals totals){
        public Review{sources=List.copyOf(sources);}
        public boolean localSnapshot(){return code==Code.REVIEWED || code==Code.QUARANTINED;}
        public boolean distributedSnapshot(){return false;}
        public boolean currentAuthority(){return false;}
        public boolean completeCaseCoverage(){return false;}
        public boolean executionTerminated(){return false;}
        public boolean repairExecutionAllowed(){return false;}
        public boolean activationAllowed(){return false;}
    }
    private record Selected(Participant source,UUID intent,UUID basis,int basisNumber,boolean stopped,Progress progress){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionHoldRestorationProgress progress;
    public CommerceRetentionHoldRestorationReview(DataSource ds,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(10);
        progress=new CommerceRetentionHoldRestorationProgress(ds,manager);
    }
    public Review review(long order,UUID request,UUID ref){
        if(order<1 || request==null || ref==null)throw new IllegalArgumentException("Invalid restoration review scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_RESTORATION_REVIEW_CALLER_TRANSACTION");
        try{return tx.execute(status->snapshot(order,request,ref));}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return failed(Code.UNAVAILABLE);}
        catch(IllegalArgumentException invalidHistory){return failed(Code.INCONSISTENT_HISTORY);}
    }
    // Shared only with version capture/check using this same data source and parent lock.
    Review snapshot(long order,UUID request,UUID ref){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RESTORATION_REVIEW_SNAPSHOT_REQUIRES_TRANSACTION");
        if(jdbc.queryForList("SELECT request_id FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref).isEmpty())return failed(Code.UNKNOWN_REQUEST);
        var plans=jdbc.queryForList("SELECT p.source,p.source_request_id,d.source_request_id AS receipt_request,d.source_hold_id FROM commerce_retention_hold_source_requests p LEFT JOIN commerce_retention_hold_delivery_receipts d ON d.request_id=p.request_id AND d.source=p.source WHERE p.request_id=? ORDER BY p.source LIMIT 8",request);
        if(plans.size()!=7)return failed(Code.INCONSISTENT_HISTORY);
        var planBySource=new EnumMap<Participant,Map<String,Object>>(Participant.class);
        for(var plan:plans){
            Participant source=Participant.valueOf((String)plan.get("source"));
            if(!CommerceRetentionSourceHolds.SOURCES.contains(source) || planBySource.put(source,plan)!=null || plan.get("source_request_id")==null || (plan.get("source_hold_id")!=null && !plan.get("source_request_id").equals(plan.get("receipt_request"))))return failed(Code.INCONSISTENT_HISTORY);
        }
        // Start at the request/source probe primary-key range; each lateral lookup
        // uses the unique basis key, instead of selecting a global intent history.
        var rows=jdbc.queryForList("SELECT i.intent_id,i.request_id AS intent_request,i.source,p.source AS basis_source,i.basis_probe_id,i.source_request_id,i.source_hold_id,p.source_request_id AS basis_request,p.source_hold_id AS basis_hold,p.probe_no,b.code,EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_stops s WHERE s.intent_id=i.intent_id) AS stopped FROM commerce_retention_hold_probes p JOIN LATERAL (SELECT i.* FROM commerce_retention_hold_repair_intents i WHERE i.basis_probe_id=p.probe_id LIMIT 1) i ON TRUE LEFT JOIN commerce_retention_hold_probe_results b ON b.probe_id=p.probe_id WHERE p.request_id=? ORDER BY p.source,p.probe_no LIMIT 57",request);
        var probes=jdbc.queryForList("SELECT p.source,p.probe_no,p.probe_id,p.source_request_id,p.source_hold_id,r.code,x.replacement_probe_id FROM commerce_retention_hold_probes p LEFT JOIN commerce_retention_hold_probe_results r USING(probe_id) LEFT JOIN commerce_retention_hold_probe_resolutions x USING(probe_id) WHERE p.request_id=? ORDER BY p.source,p.probe_no LIMIT 57",request);
        if(rows.size()>56 || probes.size()>56)return failed(Code.INCONSISTENT_HISTORY);
        var probesBySource=new EnumMap<Participant,List<Map<String,Object>>>(Participant.class);
        for(var probe:probes){
            Participant source=Participant.valueOf((String)probe.get("source"));var plan=planBySource.get(source);
            if(plan==null || plan.get("source_hold_id")==null || !plan.get("source_request_id").equals(probe.get("source_request_id")) || !plan.get("source_hold_id").equals(probe.get("source_hold_id")))return failed(Code.INCONSISTENT_HISTORY);
            var group=probesBySource.computeIfAbsent(source,key->new ArrayList<>());
            if(group.size()>=8 || ((Number)probe.get("probe_no")).intValue()!=group.size()+1 || probe.get("probe_id")==null || probe.get("code")!=null && probe.get("replacement_probe_id")!=null)return failed(Code.INCONSISTENT_HISTORY);
            group.add(probe);
        }
        for(var group:probesBySource.values())for(int n=0;n<group.size();n++)if(group.get(n).get("replacement_probe_id")!=null){
            boolean found=false;Object replacement=group.get(n).get("replacement_probe_id");
            for(int m=n+1;m<group.size();m++)if(replacement.equals(group.get(m).get("probe_id")) && ("MATCH_AS_OF_READ".equals(group.get(m).get("code")) || "MISSING_AT_SOURCE".equals(group.get(m).get("code"))))found=true;
            if(!found)return failed(Code.INCONSISTENT_HISTORY);
        }
        var selected=new ArrayList<Selected>(56);var counts=new EnumMap<Participant,Integer>(Participant.class);
        for(var row:rows){
            Participant source=Participant.valueOf((String)row.get("source"));var plan=planBySource.get(source);
            if(plan==null || !request.equals(row.get("intent_request")) || !source.name().equals(row.get("basis_source")) || !row.get("source_request_id").equals(row.get("basis_request")) || !row.get("source_hold_id").equals(row.get("basis_hold")) || plan.get("source_hold_id")==null || !plan.get("source_request_id").equals(row.get("source_request_id")) || !plan.get("source_hold_id").equals(row.get("source_hold_id")) || row.get("probe_no")==null || !"MISSING_AT_SOURCE".equals(row.get("code")) || counts.merge(source,1,Integer::sum)>8)return failed(Code.INCONSISTENT_HISTORY);
            UUID id=(UUID)row.get("intent_id");var p=progress.snapshot(order,request,ref,source,id);
            if(p.code()==CommerceRetentionHoldRestorationProgress.Code.UNAVAILABLE)return failed(Code.UNAVAILABLE);
            if(p.code()!=CommerceRetentionHoldRestorationProgress.Code.ASSESSED && p.code()!=CommerceRetentionHoldRestorationProgress.Code.STOPPED && p.code()!=CommerceRetentionHoldRestorationProgress.Code.QUARANTINED)return failed(Code.INCONSISTENT_HISTORY);
            selected.add(new Selected(source,id,(UUID)row.get("basis_probe_id"),((Number)row.get("probe_no")).intValue(),Boolean.TRUE.equals(row.get("stopped")),p));
        }
        // One final clock for all seven rows; earlier matches may expire while reading
        // other intents. Known quarantine is checked again before producing the report.
        boolean q=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_requests WHERE request_id=? AND restore_blocked) OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,request,order,order,order));
        Instant now=Instant.now();var sources=new ArrayList<Source>(7);
        int totalAttempts=0,totalPending=0,totalAck=0,totalReadPending=0,totalSuperseded=0,totalStopped=0,matchedSources=0,reviewSources=0;
        for(Participant source:Participant.values()){
            if(!CommerceRetentionSourceHolds.SOURCES.contains(source))continue;var plan=planBySource.get(source);
            var group=probesBySource.getOrDefault(source,List.of());int pending=0,superseded=0;
            for(var p:group)if(p.get("code")==null){if(p.get("replacement_probe_id")==null)pending++;else superseded++;}
            var reasons=EnumSet.noneOf(Reason.class);var intents=new ArrayList<Intent>(8);
            if(plan.get("source_hold_id")==null)reasons.add(Reason.NO_HISTORICAL_RECEIPT);
            for(var item:selected)if(item.source()==source){
                var p=at(item.progress(),now,q);intents.add(new Intent(item.intent(),item.basis(),item.basisNumber(),item.stopped(),p));
                totalAttempts+=p.attemptsUsed();totalPending+=p.pendingAttempts();totalAck+=p.historicalAcknowledgements();if(item.stopped())totalStopped++;
                if(item.stopped())reasons.add(Reason.STOPPED);
                if(p.pendingAttempts()>0)reasons.add(Reason.UNKNOWN_WRITE);
                switch(p.delivery()){
                    case NOT_ATTEMPTED->reasons.add(Reason.NOT_ATTEMPTED);
                    case DIAGNOSTIC_ONLY->reasons.add(Reason.DIAGNOSTIC_WRITE_RESULT);
                    default->{}
                }
                switch(p.inspection()){
                    case REQUIRED,OLDER_THAN_REPAIR,UNAVAILABLE->reasons.add(Reason.INSPECTION_REQUIRED);
                    case PENDING->reasons.add(Reason.INSPECTION_PENDING);
                    case STALE->reasons.add(Reason.STALE_INSPECTION);
                    case MISSING_AT_SOURCE,CONFLICT_REVIEW,UNKNOWN_PURCHASE,SOURCE_QUARANTINED->reasons.add(Reason.SOURCE_DISCREPANCY);
                    case VERSION_CHANGED->reasons.add(Reason.SOURCE_VERSION_CHANGED);
                    default->{}
                }
            }
            if(intents.isEmpty())reasons.add(Reason.NO_REPAIR_INTENT);
            if(pending>0)reasons.add(Reason.READ_PENDING);if(superseded>0)reasons.add(Reason.SUPERSEDED_READ);
            if(group.size()==8)reasons.add(Reason.PROBE_BUDGET_EXHAUSTED);if(q)reasons.add(Reason.ROOT_QUARANTINED);
            if(!intents.isEmpty() && intents.stream().allMatch(i->i.progress().inspection()==Inspection.MATCH_AS_OF_READ))matchedSources++;
            if(!reasons.isEmpty())reviewSources++;totalReadPending+=pending;totalSuperseded+=superseded;
            sources.add(new Source(source,(UUID)plan.get("source_request_id"),(UUID)plan.get("source_hold_id"),group.size(),8-group.size(),pending,superseded,group.isEmpty()?null:(UUID)group.getLast().get("probe_id"),intents,reasons));
        }
        return new Review(q?Code.QUARANTINED:Code.REVIEWED,now,sources,new Totals(7,selected.size(),totalAttempts,totalPending,totalAck,probes.size(),totalReadPending,totalSuperseded,totalStopped,matchedSources,reviewSources));
    }
    private static Progress at(Progress p,Instant now,boolean q){
        Inspection inspection=p.inspection();
        if(q)inspection=Inspection.BLOCKED;
        else if(Set.of(Inspection.MATCH_AS_OF_READ,Inspection.MISSING_AT_SOURCE,Inspection.CONFLICT_REVIEW,Inspection.UNKNOWN_PURCHASE,Inspection.SOURCE_QUARANTINED,Inspection.VERSION_CHANGED).contains(inspection) && (p.observedAt()==null || p.observedAt().isBefore(now.minusSeconds(15)) || p.observedAt().isAfter(now.plusSeconds(5))))inspection=Inspection.STALE;
        if(!q && inspection==p.inspection())return p;
        return new Progress(q?CommerceRetentionHoldRestorationProgress.Code.QUARANTINED:p.code(),p.delivery(),p.attemptsUsed(),p.pendingAttempts(),p.historicalAcknowledgements(),p.probesUsed(),p.remainingProbes(),p.pendingProbes(),p.supersededProbes(),inspection,p.latestProbeId(),p.observedAt(),p.bindingEpoch(),p.sourceRevision());
    }
    private static Review failed(Code code){return new Review(code,null,List.of(),new Totals(0,0,0,0,0,0,0,0,0,0,0));}
}
