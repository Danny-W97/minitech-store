package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionSourceHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Bounded trusted qualification of local history, never an execution permit. */
public final class CommerceRetentionHoldRepairAssessment {
    public enum Code { ASSESSED, QUARANTINED, UNKNOWN_REQUEST, INCONSISTENT_HISTORY, UNAVAILABLE }
    public enum State { NO_HISTORICAL_RECEIPT, PROBE_REQUIRED, PENDING_REVIEW, RECHECK_REQUIRED,
        MATCH_AS_OF_PROBE, MISSING_HOLD_CANDIDATE, CONFLICT_REVIEW, UNKNOWN_PURCHASE_REVIEW,
        SOURCE_QUARANTINED, ROOT_QUARANTINED }
    public record Candidate(Participant source,UUID sourceRequestId,UUID expectedHoldId,Guard guard,
                            CommerceRetentionSourceHolds.Reason reason,UUID basisProbeId,Instant observedAt,
                            long bindingEpoch,long sourceRevision){
        public boolean freshSourceCheckRequired(){return true;}
        public boolean verifiedCaseRequired(){return true;}
        public boolean executionAllowed(){return false;}
    }
    public record Source(Participant source,State state,int probesUsed,int remainingProbes,int pendingProbes,
                         int resolvedPendingProbes,int historicalDiscrepancies,UUID latestProbeId,Candidate candidate){}
    public record Assessment(Code code,List<Source> sources){
        public Assessment{sources=List.copyOf(sources);}
        public boolean historyOnly(){return true;}
        public boolean repairAllowed(){return false;}
        public boolean activationAllowed(){return false;}
        public boolean completeCaseCoverage(){return false;}
    }
    private record Probe(UUID id,CommerceRetentionHoldReconciliation.Code code,Instant time,long epoch,long revision,UUID supersededBy){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionHoldRepairAssessment(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    public Assessment assess(long order,UUID request,UUID caseReference){
        if(order<1 || request==null || caseReference==null)throw new IllegalArgumentException("Invalid repair assessment scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_REPAIR_ASSESSMENT_CALLER_TRANSACTION");
        try{return tx.execute(status->{
            var parents=jdbc.queryForList("SELECT guard,reason_code,restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,caseReference);
            if(parents.isEmpty())return failed(Code.UNKNOWN_REQUEST);var parent=parents.getFirst();
            boolean quarantined=Boolean.TRUE.equals(parent.get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
            var plans=jdbc.queryForList("SELECT p.source,p.source_request_id,r.source_request_id AS receipt_request,r.source_hold_id FROM commerce_retention_hold_source_requests p LEFT JOIN commerce_retention_hold_delivery_receipts r ON r.request_id=p.request_id AND r.source=p.source WHERE p.request_id=? ORDER BY p.source LIMIT 8",request);
            if(plans.size()!=7)return failed(Code.INCONSISTENT_HISTORY);
            var rows=jdbc.queryForList("SELECT p.source,p.probe_no,p.probe_id,p.source_request_id,p.source_hold_id,r.code,r.observed_at,r.binding_epoch,r.source_revision,x.replacement_probe_id FROM commerce_retention_hold_probes p LEFT JOIN commerce_retention_hold_probe_results r USING(probe_id) LEFT JOIN commerce_retention_hold_probe_resolutions x USING(probe_id) WHERE p.request_id=? ORDER BY p.source,p.probe_no LIMIT 57",request);
            if(rows.size()>56)return failed(Code.INCONSISTENT_HISTORY);
            var seen=EnumSet.noneOf(Participant.class);var sources=new ArrayList<Source>(7);int consumed=0;Instant now=Instant.now();
            for(var plan:plans){
                Participant source;Guard guard;CommerceRetentionSourceHolds.Reason reason;
                try{source=Participant.valueOf((String)plan.get("source"));guard=Guard.valueOf((String)parent.get("guard"));reason=CommerceRetentionSourceHolds.Reason.valueOf((String)parent.get("reason_code"));}
                catch(IllegalArgumentException invalid){return failed(Code.INCONSISTENT_HISTORY);}
                if(!CommerceRetentionSourceHolds.SOURCES.contains(source) || !seen.add(source))return failed(Code.INCONSISTENT_HISTORY);
                UUID sourceRequest=(UUID)plan.get("source_request_id"),hold=(UUID)plan.get("source_hold_id");
                if(sourceRequest==null || !CommerceRetentionSourceHolds.GUARDS.contains(guard) || (hold!=null && !sourceRequest.equals(plan.get("receipt_request"))))return failed(Code.INCONSISTENT_HISTORY);
                var probes=new ArrayList<Probe>(8);int pending=0,resolved=0,discrepancies=0;
                for(var row:rows){
                    if(!source.name().equals(row.get("source")))continue;consumed++;
                    if(hold==null || !sourceRequest.equals(row.get("source_request_id")) || !hold.equals(row.get("source_hold_id")) || ((Number)row.get("probe_no")).intValue()!=probes.size()+1 || probes.size()>=8)return failed(Code.INCONSISTENT_HISTORY);
                    Probe probe;
                    UUID supersededBy=(UUID)row.get("replacement_probe_id");
                    if(row.get("code")==null){if(supersededBy==null)pending++;else resolved++;probe=new Probe((UUID)row.get("probe_id"),null,null,0,0,supersededBy);}
                    else{
                        if(supersededBy!=null)return failed(Code.INCONSISTENT_HISTORY);
                        CommerceRetentionHoldReconciliation.Code code;
                        try{code=CommerceRetentionHoldReconciliation.Code.valueOf((String)row.get("code"));}catch(IllegalArgumentException invalid){return failed(Code.INCONSISTENT_HISTORY);}
                        Instant time=row.get("observed_at")==null?null:((Timestamp)row.get("observed_at")).toInstant();long epoch=((Number)row.get("binding_epoch")).longValue(),revision=((Number)row.get("source_revision")).longValue();
                        boolean observed=Set.of(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,CommerceRetentionHoldReconciliation.Code.SOURCE_CONFLICT,CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN,CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED).contains(code);
                        boolean versioned=observed && code!=CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN;
                        if(observed!=(time!=null) || revision<0 || (versioned?epoch<1:epoch!=0 || revision!=0))return failed(Code.INCONSISTENT_HISTORY);
                        if(code!=CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ)discrepancies++;
                        probe=new Probe((UUID)row.get("probe_id"),code,time,epoch,revision,null);
                    }
                    if(probe.id()==null)return failed(Code.INCONSISTENT_HISTORY);probes.add(probe);
                }
                for(int i=0;i<probes.size();i++)if(probes.get(i).supersededBy()!=null){
                    UUID replacement=probes.get(i).supersededBy();boolean validReplacement=false;
                    for(int n=i+1;n<probes.size();n++)if(replacement.equals(probes.get(n).id()) && (probes.get(n).code()==CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ || probes.get(n).code()==CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE))validReplacement=true;
                    if(!validReplacement)return failed(Code.INCONSISTENT_HISTORY);
                }
                Probe latest=probes.isEmpty()?null:probes.getLast();State state;Candidate candidate=null;
                if(quarantined)state=State.ROOT_QUARANTINED;
                else if(hold==null)state=State.NO_HISTORICAL_RECEIPT;
                else if(pending>0)state=State.PENDING_REVIEW;
                else if(latest==null)state=State.PROBE_REQUIRED;
                else{
                    state=switch(latest.code()){
                        case SOURCE_CONFLICT->State.CONFLICT_REVIEW;case SOURCE_UNKNOWN->State.UNKNOWN_PURCHASE_REVIEW;
                        case SOURCE_QUARANTINED->State.SOURCE_QUARANTINED;case ROOT_QUARANTINED->State.ROOT_QUARANTINED;
                        case MATCH_AS_OF_READ,MISSING_AT_SOURCE->{
                            boolean fresh=latest.time()!=null && !latest.time().isBefore(now.minusSeconds(15)) && !latest.time().isAfter(now.plusSeconds(5));
                            yield !fresh?State.RECHECK_REQUIRED:latest.code()==CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ?State.MATCH_AS_OF_PROBE:State.MISSING_HOLD_CANDIDATE;
                        }
                        default->State.RECHECK_REQUIRED;
                    };
                    if(state==State.MISSING_HOLD_CANDIDATE)candidate=new Candidate(source,sourceRequest,hold,guard,reason,latest.id(),latest.time(),latest.epoch(),latest.revision());
                }
                sources.add(new Source(source,state,probes.size(),8-probes.size(),pending,resolved,discrepancies,latest==null?null:latest.id(),candidate));
            }
            if(consumed!=rows.size())return failed(Code.INCONSISTENT_HISTORY);
            return new Assessment(quarantined?Code.QUARANTINED:Code.ASSESSED,sources);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return failed(Code.UNAVAILABLE);}
    }
    private static Assessment failed(Code code){return new Assessment(code,List.of());}
}
