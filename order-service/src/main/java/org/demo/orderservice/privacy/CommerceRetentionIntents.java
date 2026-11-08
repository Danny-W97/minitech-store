package org.demo.orderservice.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Durable preparation and unstarted cancellation only. No API can authorize or execute a mutation. */
public final class CommerceRetentionIntents {
    public enum State { WAITING_AUTHORIZATION, STOP_REQUESTED, CANCELLED, QUARANTINED }
    public enum Code { PREPARED, UPDATED, IDEMPOTENT, UNCHANGED, CONFLICT, STALE, BLOCKED, QUARANTINED, UNAVAILABLE }
    public record Intent(UUID id,Subject subject,Phase phase,State state,long revision,boolean restoreBlocked) {}
    public record Result(Code code,Intent intent) {
        public boolean activationAllowed(){return false;}
        public boolean executionTerminated(){return false;}
    }
    enum CheckCode { CURRENT, STALE, STOPPED, BLOCKED, QUARANTINED }
    record PreparedCheckpoint(CheckCode code) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionHolds holds;
    public CommerceRetentionIntents(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);holds=new CommerceRetentionHolds(ds,manager);}

    public Result prepare(Subject subject,Phase phase,UUID intentId){
        input(subject,phase,intentId);
        return database(()->tx.execute(status->{
            var job=lockSubject(subject,phase);if(job==null)return result(Code.STALE,null);
            var previous=own(intentId,subject,phase);
            if(previous!=null)return result(Boolean.TRUE.equals(previous.get("restore_blocked"))?Code.QUARANTINED:Code.IDEMPOTENT,view(previous,subject,phase));
            if(exists("SELECT EXISTS(SELECT 1 FROM commerce_retention_intents WHERE intent_id=? OR (order_id=? AND generation=? AND phase=?))",intentId,subject.orderId(),subject.chainGeneration(),phase.name()))return result(Code.CONFLICT,null);
            if(!Boolean.TRUE.equals(job.get("current")) || n(job,"live_version")!=subject.orderVersion() || n(job,"live_customer")!=subject.customerId())return result(Code.STALE,null);
            var h=holds.snapshot(subject.orderId(),subject.customerId(),phase);
            if(h.quarantined() || !h.blockers().isEmpty() || fenced(subject) || phase==Phase.REDACTION && Boolean.TRUE.equals(job.get("privacy_redacted")))return result(Code.BLOCKED,null);
            jdbc.update("""
                INSERT INTO commerce_retention_intents(intent_id,order_id,generation,order_version,phase,policy,
                  prepared_proof_revision,order_hold_revision,customer_hold_revision,last_request_id)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,intentId,subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase.name(),POLICY,
                    n(job,"proof_revision"),h.orderRevision(),h.customerRevision(),intentId);
            return result(Code.PREPARED,view(own(intentId,subject,phase),subject,phase));
        }));
    }
    public Result requestStop(Subject subject,Phase phase,UUID intentId,long expectedRevision,UUID requestId){
        return transition(subject,phase,intentId,expectedRevision,requestId,State.STOP_REQUESTED);
    }
    public Result cancelUnstarted(Subject subject,Phase phase,UUID intentId,long expectedRevision,UUID requestId){
        return transition(subject,phase,intentId,expectedRevision,requestId,State.CANCELLED);
    }
    /** Local preparation check only. Caller keeps these SQL locks through its checkpoint/assessment. */
    PreparedCheckpoint checkPrepared(Subject s,Phase phase,UUID id,long expectedRevision){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_INTENT_CHECK_TRANSACTION_REQUIRED");
        var job=lockSubject(s,phase);if(job==null)return new PreparedCheckpoint(CheckCode.STALE);
        var row=own(id,s,phase);if(row==null)return new PreparedCheckpoint(CheckCode.STALE);
        if(Boolean.TRUE.equals(row.get("restore_blocked")))return new PreparedCheckpoint(CheckCode.QUARANTINED);
        var state=State.valueOf((String)row.get("state"));
        if(state!=State.WAITING_AUTHORIZATION)return new PreparedCheckpoint(CheckCode.STOPPED);
        if(n(row,"revision")!=expectedRevision || !Boolean.TRUE.equals(job.get("current"))
                || n(job,"live_version")!=s.orderVersion() || n(job,"live_customer")!=s.customerId())return new PreparedCheckpoint(CheckCode.STALE);
        var h=holds.snapshot(s.orderId(),s.customerId(),phase);
        if(h.quarantined() || !h.blockers().isEmpty() || fenced(s) || phase==Phase.REDACTION && Boolean.TRUE.equals(job.get("privacy_redacted")))return new PreparedCheckpoint(CheckCode.BLOCKED);
        if(n(row,"prepared_proof_revision")!=n(job,"proof_revision") || n(row,"order_hold_revision")!=h.orderRevision()
                || n(row,"customer_hold_revision")!=h.customerRevision())return new PreparedCheckpoint(CheckCode.STALE);
        return new PreparedCheckpoint(CheckCode.CURRENT);
    }
    private Result transition(Subject s,Phase phase,UUID id,long expected,UUID request,State target){
        input(s,phase,id);Objects.requireNonNull(request);if(expected<0)throw new IllegalArgumentException("Invalid intent revision");
        return database(()->tx.execute(status->{
            if(lockSubject(s,phase)==null)return result(Code.STALE,null);
            var row=own(id,s,phase);if(row==null)return result(Code.STALE,null);var current=view(row,s,phase);
            if(current.restoreBlocked())return result(Code.QUARANTINED,current);
            var prior=jdbc.queryForList("SELECT intent_id,state FROM commerce_retention_intent_events WHERE request_id=?",request);
            if(!prior.isEmpty())return result(id.equals(prior.getFirst().get("intent_id")) && target.name().equals(prior.getFirst().get("state"))?Code.IDEMPOTENT:Code.CONFLICT,current);
            if(current.state()==target || current.state()==State.CANCELLED)return result(Code.UNCHANGED,current);
            if(current.revision()!=expected)return result(Code.CONFLICT,current);
            if(target==State.STOP_REQUESTED && current.state()!=State.WAITING_AUTHORIZATION)return result(Code.CONFLICT,current);
            jdbc.update("UPDATE commerce_retention_intents SET state=?,revision=revision+1,last_request_id=?,updated_at=clock_timestamp() WHERE intent_id=? AND revision=?",target.name(),request,id,expected);
            return result(Code.UPDATED,view(own(id,s,phase),s,phase));
        }));
    }
    private Map<String,Object> lockSubject(Subject s,Phase phase){
        var orders=jdbc.queryForList("SELECT version,coalesce(customer_id,0) AS customer_id,privacy_redacted FROM orders WHERE id=? FOR NO KEY UPDATE",s.orderId());
        if(orders.isEmpty())return null;
        var jobs=jdbc.queryForList("SELECT proof_revision,current FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND coalesce(customer_id,0)=? AND phase=? AND policy=? FOR UPDATE",s.orderId(),s.chainGeneration(),s.orderVersion(),s.customerId(),phase.name(),POLICY);
        if(jobs.isEmpty())return null;var row=new HashMap<String,Object>(jobs.getFirst());
        row.put("live_version",orders.getFirst().get("version"));row.put("live_customer",orders.getFirst().get("customer_id"));row.put("privacy_redacted",orders.getFirst().get("privacy_redacted"));return row;
    }
    private Map<String,Object> own(UUID id,Subject s,Phase phase){
        var rows=jdbc.queryForList("SELECT * FROM commerce_retention_intents WHERE intent_id=? AND order_id=? AND generation=? AND order_version=? AND phase=? AND policy=? FOR UPDATE",id,s.orderId(),s.chainGeneration(),s.orderVersion(),phase.name(),POLICY);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private boolean fenced(Subject s){return exists("SELECT EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=?) OR EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=?) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked) OR EXISTS(SELECT 1 FROM commerce_retention_bindings WHERE order_id=? AND restore_blocked)",s.customerId(),s.orderId(),s.orderId(),s.orderId());}
    private boolean exists(String sql,Object...args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
    private Result database(Supplier<Result> action){try{return action.get();}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,null);}}
    private static void input(Subject s,Phase phase,UUID id){Objects.requireNonNull(s);Objects.requireNonNull(phase);Objects.requireNonNull(id);if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_INTENT_CALLER_TRANSACTION");}
    private static Result result(Code code,Intent intent){return new Result(code,intent);}
    private static Intent view(Map<String,Object> row,Subject s,Phase phase){return new Intent((UUID)row.get("intent_id"),s,phase,State.valueOf((String)row.get("state")),n(row,"revision"),Boolean.TRUE.equals(row.get("restore_blocked")));}
    private static long n(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
}
