package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionSourceHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;

/** Trusted metadata planning only; never proof of worker termination, case authority or repair. */
public final class CommerceRetentionHoldRecoveryPlanning {
    public enum Code { RECORDED, PREPARED, IDEMPOTENT, UNKNOWN_REQUEST, UNKNOWN_PROBE, NOT_PENDING,
        INVALID_REPLACEMENT, NOT_CANDIDATE, QUARANTINED, CONFLICT, UNAVAILABLE, HISTORY }
    public record Result(Code code,UUID id,UUID basisProbeId){
        public boolean executionTerminated(){return false;}
        public boolean repairAllowed(){return false;}
        public boolean activationAllowed(){return false;}
        public boolean completeCaseCoverage(){return false;}
    }
    public record Resolution(UUID probeId,UUID resolutionId,UUID replacementProbeId){}
    public record Intent(UUID intentId,UUID basisProbeId,UUID sourceRequestId,UUID expectedHoldId){}
    public record History(Code code,List<Resolution> resolutions,List<Intent> intents){
        public History{resolutions=List.copyOf(resolutions);intents=List.copyOf(intents);}
        public boolean historyOnly(){return true;}
        public boolean repairAllowed(){return false;}
        public boolean activationAllowed(){return false;}
    }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionHoldRecoveryPlanning(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    private static void valid(long order,UUID request,UUID ref,Participant source){
        if(order<1 || request==null || ref==null || source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source))throw new IllegalArgumentException("Invalid hold recovery scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_RECOVERY_CALLER_TRANSACTION");
    }
    public Result supersede(long order,UUID request,UUID ref,Participant source,UUID probe,UUID replacement,UUID resolution){
        valid(order,request,ref,source);if(probe==null || replacement==null || resolution==null || probe.equals(replacement))throw new IllegalArgumentException("Invalid hold probe resolution");
        try{return tx.execute(status->{
            var failure=scope(order,request,ref);if(failure!=null)return result(failure,resolution,null);
            if(jdbc.queryForList("SELECT probe_id FROM commerce_retention_hold_probes WHERE request_id=? AND source=? AND probe_id=?",request,source.name(),probe).isEmpty())return result(Code.UNKNOWN_PROBE,resolution,null);
            var existing=jdbc.queryForList("SELECT probe_id,resolution_id,replacement_probe_id FROM commerce_retention_hold_probe_resolutions WHERE probe_id=? OR resolution_id=?",probe,resolution);
            if(!existing.isEmpty()){
                var row=existing.getFirst();boolean same=existing.size()==1 && probe.equals(row.get("probe_id")) && resolution.equals(row.get("resolution_id")) && replacement.equals(row.get("replacement_probe_id"));
                return result(same?Code.IDEMPOTENT:Code.CONFLICT,resolution,same?replacement:null);
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_probe_results WHERE probe_id=?)",Boolean.class,probe)))return result(Code.NOT_PENDING,resolution,null);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT retention_hold_probe_supersedable(?,?)",Boolean.class,probe,replacement)))return result(Code.INVALID_REPLACEMENT,resolution,null);
            jdbc.update("INSERT INTO commerce_retention_hold_probe_resolutions(probe_id,resolution_id,replacement_probe_id) VALUES(?,?,?)",probe,resolution,replacement);
            return result(Code.RECORDED,resolution,replacement);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,resolution,null);}
    }
    public Result prepare(long order,UUID request,UUID ref,Participant source,UUID intent){
        valid(order,request,ref,source);if(intent==null)throw new IllegalArgumentException("Invalid hold repair intent");
        try{return tx.execute(status->{
            var failure=scope(order,request,ref);if(failure!=null)return result(failure,intent,null);
            var existing=jdbc.queryForList("SELECT request_id,source,basis_probe_id FROM commerce_retention_hold_repair_intents WHERE intent_id=?",intent);
            if(!existing.isEmpty()){
                var row=existing.getFirst();boolean same=request.equals(row.get("request_id")) && source.name().equals(row.get("source"));
                return result(same?Code.IDEMPOTENT:Code.CONFLICT,intent,same?(UUID)row.get("basis_probe_id"):null);
            }
            var probes=jdbc.queryForList("SELECT probe_id,source_request_id,source_hold_id FROM commerce_retention_hold_probes WHERE request_id=? AND source=? ORDER BY probe_no DESC LIMIT 1",request,source.name());
            if(probes.isEmpty())return result(Code.NOT_CANDIDATE,intent,null);var p=probes.getFirst();UUID basis=(UUID)p.get("probe_id");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT retention_hold_repair_candidate(?)",Boolean.class,basis)))return result(Code.NOT_CANDIDATE,intent,null);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_repair_intents WHERE basis_probe_id=?)",Boolean.class,basis)))return result(Code.CONFLICT,intent,null);
            jdbc.update("INSERT INTO commerce_retention_hold_repair_intents(intent_id,request_id,source,basis_probe_id,source_request_id,source_hold_id) VALUES(?,?,?,?,?,?)",intent,request,source.name(),basis,p.get("source_request_id"),p.get("source_hold_id"));
            return result(Code.PREPARED,intent,basis);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,intent,null);}
    }
    public History inspect(long order,UUID request,UUID ref,Participant source){
        valid(order,request,ref,source);
        try{return tx.execute(status->{
            var failure=scope(order,request,ref);if(failure==Code.UNKNOWN_REQUEST)return new History(failure,List.of(),List.of());
            var resolutions=jdbc.query("SELECT r.probe_id,r.resolution_id,r.replacement_probe_id FROM commerce_retention_hold_probe_resolutions r JOIN commerce_retention_hold_probes p USING(probe_id) WHERE p.request_id=? AND p.source=? ORDER BY p.probe_no LIMIT 8",(rs,n)->new Resolution(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)),request,source.name());
            var intents=jdbc.query("SELECT i.intent_id,i.basis_probe_id,i.source_request_id,i.source_hold_id FROM commerce_retention_hold_probes p JOIN commerce_retention_hold_repair_intents i ON i.basis_probe_id=p.probe_id WHERE p.request_id=? AND p.source=? ORDER BY p.probe_no LIMIT 8",(rs,n)->new Intent(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)),request,source.name());
            return new History(failure==Code.QUARANTINED?failure:Code.HISTORY,resolutions,intents);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return new History(Code.UNAVAILABLE,List.of(),List.of());}
    }
    private Code scope(long order,UUID request,UUID ref){
        var rows=jdbc.queryForList("SELECT restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref);
        if(rows.isEmpty())return Code.UNKNOWN_REQUEST;
        boolean blocked=Boolean.TRUE.equals(rows.getFirst().get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
        return blocked?Code.QUARANTINED:null;
    }
    private static Result result(Code code,UUID id,UUID basis){return new Result(code,id,basis);}
}
