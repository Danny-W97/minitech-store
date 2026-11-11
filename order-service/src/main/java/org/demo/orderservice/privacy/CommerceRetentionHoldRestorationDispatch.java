package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.SourceHoldRestorationProtocol.*;

/** Explicit one-call coordinator. Immutable attempts precede RPC; retries never resend RESTORE.
 * No scheduler, public operator API, case issuer, ownership proof or global clearance. */
public final class CommerceRetentionHoldRestorationDispatch {
    public enum Code { RECORDED, PENDING, QUARANTINED, STOPPED, UNKNOWN_INTENT, CONFLICT, RECHECK_REQUIRED, REPLAY_REQUIRED, LIMIT_REACHED, AUTHORIZATION_REQUIRED, UNAVAILABLE }
    @FunctionalInterface public interface Transport { Reply restore(Command command); }
    public record Result(Code code,UUID attemptId,String outcome){
        public boolean activationAllowed(){return false;}
        public boolean currentAuthority(){return false;}
        public boolean executionTerminated(){return false;}
    }
    public record Entry(UUID attemptId,int number,Mode mode,String outcome){}
    public record History(Code code,List<Entry> attempts){public History{attempts=List.copyOf(attempts);}public boolean activationAllowed(){return false;}public boolean currentAuthority(){return false;}}
    private record Frame(Code failure,Command command,UUID basis){}
    private record Work(Result result,Command command){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final SourceHoldCaseAuthorization authority;
    public CommerceRetentionHoldRestorationDispatch(DataSource ds,PlatformTransactionManager manager,SourceHoldCaseAuthorization authority){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);this.authority=Objects.requireNonNull(authority);
    }
    public Result step(long order,UUID request,UUID ref,Participant source,UUID intent,UUID attempt,Mode mode,String authorization,Transport remote){
        scope(order,request,ref,source,intent);if(attempt==null || mode==null || authorization==null || authorization.length()>4096 || remote==null)throw new IllegalArgumentException("Invalid restoration dispatch");outside();
        final Work work;
        try{work=tx.execute(status->{
            var f=frame(order,request,ref,source,intent,attempt,mode,authorization);
            if(f.failure()!=null)return new Work(result(f.failure(),attempt,null),null);
            var existing=stored(intent,attempt,mode);if(existing!=null)return new Work(existing,null);
            var collision=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_attempts WHERE attempt_id=?)",Boolean.class,attempt);
            if(Boolean.TRUE.equals(collision))return new Work(result(Code.CONFLICT,attempt,null),null);
            final SourceHoldCaseAuthorization.Authorization permit;
            try{permit=authority.verify(f.command());if(mode==Mode.RESTORE && !authority.active(permit))return new Work(result(Code.AUTHORIZATION_REQUIRED,attempt,null),null);}
            catch(IllegalArgumentException denied){return new Work(result(Code.AUTHORIZATION_REQUIRED,attempt,null),null);}
            var first=jdbc.queryForList("SELECT scope_hash,case_authorization_id FROM commerce_retention_hold_restoration_attempts WHERE intent_id=? AND attempt_no=1",intent);
            if(first.isEmpty()){
                if(mode!=Mode.RESTORE)return new Work(result(Code.RECHECK_REQUIRED,attempt,null),null);
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT retention_hold_repair_candidate(?)",Boolean.class,f.basis())))return new Work(result(Code.RECHECK_REQUIRED,attempt,null),null);
            }else{
                if(mode!=Mode.REPLAY_ONLY)return new Work(result(Code.REPLAY_REQUIRED,attempt,null),null);
                if(!scopeHash(f.command()).equals(first.getFirst().get("scope_hash")) || !permit.id().equals(first.getFirst().get("case_authorization_id")))return new Work(result(Code.CONFLICT,attempt,null),null);
            }
            var completed=success(intent);if(completed!=null)return new Work(completed,null);
            int n=jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_attempts WHERE intent_id=?",Integer.class,intent);
            if(n>=8)return new Work(result(Code.LIMIT_REACHED,attempt,null),null);
            jdbc.update("INSERT INTO commerce_retention_hold_restoration_attempts(intent_id,attempt_id,attempt_no,mode,scope_hash,case_authorization_id) VALUES(?,?,?,?,?,?)",intent,attempt,n+1,mode.name(),scopeHash(f.command()),permit.id());
            return new Work(null,f.command());
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,attempt,null);}
        if(work.result()!=null)return work.result();
        // Acknowledged commit precedes transport; no root transaction spans network I/O.
        try{var check=tx.execute(status->frame(order,request,ref,source,intent,attempt,mode,authorization));
            if(check.failure()!=null)return finish(order,request,ref,source,intent,attempt,mode,authorization,null);
            if(mode==Mode.RESTORE && !authority.active(authority.verify(work.command())))return result(Code.AUTHORIZATION_REQUIRED,attempt,null);
        }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,attempt,null);}
        Reply received=null;
        try{received=validate(remote.restore(work.command()),work.command());if(mode==Mode.REPLAY_ONLY && received.code()==SourceHoldRestorationProtocol.Code.RESTORED)received=null;}
        catch(RuntimeException unavailable){/* Unknown outcome: preserve pending; explicit REPLAY_ONLY may inspect it. */}
        return finish(order,request,ref,source,intent,attempt,mode,authorization,received);
    }
    private Result finish(long order,UUID request,UUID ref,Participant source,UUID intent,UUID attempt,Mode mode,String authorization,Reply reply){
        try{return tx.execute(status->{
            var f=frame(order,request,ref,source,intent,attempt,mode,authorization);
            String code=f.failure()==Code.QUARANTINED?"ROOT_QUARANTINED":f.failure()==Code.STOPPED?"ROOT_STOPPED":null;
            if(f.failure()!=null && code==null)return result(f.failure(),attempt,null);
            if(code==null && reply==null)return result(Code.PENDING,attempt,null);
            jdbc.update("INSERT INTO commerce_retention_hold_restoration_results(attempt_id,code,observed_at,binding_epoch,source_revision) VALUES(?,?,?,?,?)",attempt,code==null?reply.code().name():code,code==null?java.sql.Timestamp.from(reply.observedAt()):null,code==null?reply.bindingEpoch():0,code==null?reply.sourceRevision():0);
            return result(code==null?Code.RECORDED:f.failure(),attempt,code==null?reply.code().name():code);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,attempt,null);}
    }
    public History inspect(long order,UUID request,UUID ref,Participant source,UUID intent){
        scope(order,request,ref,source,intent);outside();
        try{return tx.execute(status->{
            var f=frame(order,request,ref,source,intent,UUID.randomUUID(),Mode.REPLAY_ONLY,"");
            if(f.failure()!=null && f.failure()!=Code.QUARANTINED && f.failure()!=Code.STOPPED)return new History(f.failure(),List.of());
            var entries=jdbc.query("SELECT a.attempt_id,a.attempt_no,a.mode,r.code FROM commerce_retention_hold_restoration_attempts a LEFT JOIN commerce_retention_hold_restoration_results r USING(attempt_id) WHERE a.intent_id=? ORDER BY a.attempt_no LIMIT 8",(rs,n)->new Entry((UUID)rs.getObject(1),rs.getInt(2),Mode.valueOf(rs.getString(3)),rs.getString(4)),intent);
            return new History(f.failure()==null?Code.RECORDED:f.failure(),entries);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return new History(Code.UNAVAILABLE,List.of());}
    }
    public Result stop(long order,UUID request,UUID ref,Participant source,UUID intent,UUID stop){
        scope(order,request,ref,source,intent);if(stop==null)throw new IllegalArgumentException("Invalid restoration stop");outside();
        try{return tx.execute(status->{
            var f=frame(order,request,ref,source,intent,stop,Mode.REPLAY_ONLY,"");
            if(f.failure()!=null && f.failure()!=Code.QUARANTINED && f.failure()!=Code.STOPPED)return result(f.failure(),null,null);
            var old=jdbc.queryForList("SELECT stop_id FROM commerce_retention_hold_restoration_stops WHERE intent_id=?",intent);
            if(!old.isEmpty())return result(old.getFirst().get("stop_id").equals(stop)?Code.STOPPED:Code.CONFLICT,null,null);
            jdbc.update("INSERT INTO commerce_retention_hold_restoration_stops(intent_id,stop_id) VALUES(?,?)",intent,stop);return result(Code.STOPPED,null,null);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,null,null);}
    }
    private Frame frame(long order,UUID request,UUID ref,Participant source,UUID intent,UUID attempt,Mode mode,String authorization){
        var parents=jdbc.queryForList("SELECT guard,reason_code,restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref);
        if(parents.isEmpty())return new Frame(Code.UNKNOWN_INTENT,null,null);
        var rows=jdbc.queryForList("SELECT i.basis_probe_id,i.source_request_id,i.source_hold_id,r.binding_epoch,r.source_revision FROM commerce_retention_hold_repair_intents i JOIN commerce_retention_hold_probe_results r ON r.probe_id=i.basis_probe_id AND r.code='MISSING_AT_SOURCE' WHERE i.intent_id=? AND i.request_id=? AND i.source=?",intent,request,source.name());
        if(rows.size()!=1)return new Frame(Code.UNKNOWN_INTENT,null,null);var parent=parents.getFirst();var row=rows.getFirst();
        boolean q=Boolean.TRUE.equals(parent.get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
        if(q)return new Frame(Code.QUARANTINED,null,null);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_stops WHERE intent_id=?)",Boolean.class,intent)))return new Frame(Code.STOPPED,null,null);
        var d=new SourceHoldDeliveryProtocol.Command(source,order,(UUID)row.get("source_request_id"),Guard.valueOf((String)parent.get("guard")),CommerceRetentionSourceHolds.Reason.valueOf((String)parent.get("reason_code")));
        return new Frame(null,new Command(d,intent,ref,(UUID)row.get("source_hold_id"),((Number)row.get("binding_epoch")).longValue(),((Number)row.get("source_revision")).longValue(),attempt,authorization,mode),(UUID)row.get("basis_probe_id"));
    }
    private Result stored(UUID intent,UUID attempt,Mode mode){
        var rows=jdbc.queryForList("SELECT a.mode,r.code FROM commerce_retention_hold_restoration_attempts a LEFT JOIN commerce_retention_hold_restoration_results r USING(attempt_id) WHERE a.intent_id=? AND a.attempt_id=?",intent,attempt);
        if(rows.isEmpty())return null;var row=rows.getFirst();if(!mode.name().equals(row.get("mode")))return result(Code.CONFLICT,attempt,null);
        return result(row.get("code")==null?Code.PENDING:Code.RECORDED,attempt,(String)row.get("code"));
    }
    private Result success(UUID intent){var rows=jdbc.queryForList("SELECT r.attempt_id,r.code FROM commerce_retention_hold_restoration_results r JOIN commerce_retention_hold_restoration_attempts a USING(attempt_id) WHERE a.intent_id=? AND r.code IN ('RESTORED','REPLAYED') ORDER BY a.attempt_no LIMIT 1",intent);return rows.isEmpty()?null:result(Code.RECORDED,(UUID)rows.getFirst().get("attempt_id"),(String)rows.getFirst().get("code"));}
    private static void scope(long order,UUID request,UUID ref,Participant source,UUID intent){if(order<1 || request==null || ref==null || source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source) || intent==null)throw new IllegalArgumentException("Invalid restoration scope");}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_RESTORATION_DISPATCH_CALLER_TRANSACTION");}
    private static Result result(Code code,UUID attempt,String outcome){return new Result(code,attempt,outcome);}
}
