package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.*;
import org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.Code;

/** One authenticated source read per call; records historical observations and blockers, never clearance. */
public final class CommerceRetentionSourceGuardCollector {
    public enum Result { WAITING_PROOF, BLOCKED, STALE, UNAVAILABLE }
    @FunctionalInterface public interface Source { Reply collect(Command command); }
    private record Snapshot(Result failure,PeerRetentionProtocol.Scope scope,long proofRevision,CommerceRetentionHolds.Snapshot holds) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionJobs jobs;private final CommerceRetentionHolds holds;
    public CommerceRetentionSourceGuardCollector(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);jobs=new CommerceRetentionJobs(ds,manager);holds=new CommerceRetentionHolds(ds,manager);}
    public Result collect(Subject subject,Phase phase,Participant source,Source remote){
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);Objects.requireNonNull(remote);
        if(!SOURCES.contains(source))throw new IllegalArgumentException("Unsupported guard source");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_GUARD_CALLER_TRANSACTION");
        try{return collectCurrent(subject,phase,source,remote);}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return Result.UNAVAILABLE;}
    }
    private Result collectCurrent(Subject subject,Phase phase,Participant source,Source remote){
        var before=tx.execute(status->snapshot(subject,phase,source));if(before.failure()!=null)return before.failure();
        var command=new Command(before.scope(),POLICY,UUID.randomUUID());final Reply reply;
        try{reply=validate(remote.collect(command),command);}catch(RuntimeException unavailable){return Result.UNAVAILABLE;}
        return tx.execute(status->{
            var after=snapshot(subject,phase,source);if(after.failure()!=null)return after.failure();
            if(!before.equals(after))return Result.STALE;
            // Recheck freshness after waiting for the local SQL locks too.
            try{validate(reply,command);}catch(IllegalArgumentException expired){return Result.UNAVAILABLE;}
            if(reply.code()!=Code.OBSERVED){jobs.invalidate(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase);return Result.STALE;}
            var archived=new CommerceRetentionSourceReceipts(jdbc.getDataSource()).record(command,reply);
            if(archived==CommerceRetentionSourceReceipts.Receipt.CONFLICT){
                jobs.invalidate(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase);return Result.STALE;
            }
            boolean blocked=false;
            for(var entry:reply.states().entrySet())if(entry.getValue()==State.BLOCKED){
                var receipt=jobs.record(entry.getKey(),new GuardProof(subject,phase,POLICY,State.BLOCKED));
                if(receipt==CommerceRetentionJobs.Receipt.CONFLICT || receipt==CommerceRetentionJobs.Receipt.STALE)return Result.STALE;
                blocked=true;
            }
            return blocked?Result.BLOCKED:Result.WAITING_PROOF;
        });
    }
    private Snapshot snapshot(Subject s,Phase phase,Participant source){
        var rows=jdbc.queryForList("SELECT customer_id,version,privacy_redacted FROM orders WHERE id=? FOR NO KEY UPDATE",s.orderId());
        if(rows.isEmpty() || customer(rows.getFirst())!=s.customerId() || n(rows.getFirst(),"version")!=s.orderVersion())return failed(Result.STALE);
        var j=jdbc.queryForList("SELECT proof_revision FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND coalesce(customer_id,0)=? AND phase=? AND policy=? AND current FOR UPDATE",s.orderId(),s.chainGeneration(),s.orderVersion(),s.customerId(),phase.name(),POLICY);
        if(j.isEmpty())return failed(Result.STALE);
        var h=holds.snapshot(s.orderId(),s.customerId(),phase);
        if(Boolean.TRUE.equals(rows.getFirst().get("privacy_redacted")) || h.quarantined() || !h.blockers().isEmpty()
                || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=?) OR EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=?) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,s.orderId(),s.customerId(),s.orderId())))return failed(Result.BLOCKED);
        var bindings=jdbc.queryForList("SELECT bound_epoch,source_revision FROM commerce_retention_bindings WHERE order_id=? AND generation=? AND source=? AND order_version=? AND phase=? AND policy=? AND state='BOUND' AND NOT restore_blocked AND NOT invalidated FOR SHARE",s.orderId(),s.chainGeneration(),source.name(),s.orderVersion(),phase.name(),POLICY);
        if(bindings.size()!=1)return failed(Result.STALE);var b=bindings.getFirst();
        return new Snapshot(null,new PeerRetentionProtocol.Scope(source,s.orderId(),s.chainGeneration(),s.orderVersion(),phase,n(b,"bound_epoch"),n(b,"source_revision")),n(j.getFirst(),"proof_revision"),h);
    }
    private static Snapshot failed(Result result){return new Snapshot(result,null,-1,null);}
    private static long n(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static long customer(Map<String,Object> row){return row.get("customer_id")==null?0:n(row,"customer_id");}
}
