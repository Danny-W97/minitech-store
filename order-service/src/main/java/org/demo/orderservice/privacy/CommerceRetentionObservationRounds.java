package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Bounded observation history and explicit cleanup ownership; never authorizes execution. */
public final class CommerceRetentionObservationRounds {
    public enum Code { CREATED, IDEMPOTENT, PENDING_ROUND, LIMIT_REACHED, STALE, UNAVAILABLE }
    public record Lease(PeerRetentionFenceProtocol.Scope scope,boolean releaseAcknowledged) {}
    public record Snapshot(UUID roundId,UUID intentId,long intentRevision,int ordinal,boolean finished,
            boolean quarantined,boolean cleanupOnly,int recoveryAttempts,Map<Participant,Lease> leases) {
        public Snapshot { leases=Map.copyOf(leases); }
        public boolean activationAllowed(){return false;}
        public boolean executionTerminated(){return false;}
    }
    public enum RecoveryCode { CLAIMED, BUSY, FINISHED, LIMIT_REACHED, STALE, QUARANTINED, UNAVAILABLE }
    public record RecoveryWork(Subject subject,Phase phase,UUID intentId,UUID roundId,UUID token) {}
    public record RecoveryClaim(RecoveryCode code,RecoveryWork work) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionIntents intents;
    public CommerceRetentionObservationRounds(DataSource ds,PlatformTransactionManager manager){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
        intents=new CommerceRetentionIntents(ds,manager);
    }
    public Code begin(Subject s,Phase phase,UUID intent,long revision,UUID round){
        input(s,phase,intent,round);if(revision<0)throw new IllegalArgumentException("Invalid round revision");
        try{return tx.execute(status->{
            if(intents.checkPrepared(s,phase,intent,revision).code()!=CommerceRetentionIntents.CheckCode.CURRENT)return Code.STALE;
            var previous=own(s,phase,intent,round);
            if(!previous.isEmpty())return n(previous.getFirst(),"intent_revision")==revision && !blocked(previous.getFirst())?Code.IDEMPOTENT:Code.STALE;
            if(!jdbc.queryForList("SELECT 1 FROM commerce_retention_observation_rounds WHERE intent_id=? AND finished_at IS NULL LIMIT 1",intent).isEmpty())return Code.PENDING_ROUND;
            int slot=jdbc.queryForObject("SELECT coalesce(max(ordinal),0)+1 FROM commerce_retention_observation_rounds WHERE intent_id=?",Integer.class,intent);
            if(slot>8)return Code.LIMIT_REACHED;
            jdbc.update("INSERT INTO commerce_retention_observation_rounds(round_id,intent_id,intent_revision,ordinal) VALUES(?,?,?,?)",round,intent,revision,slot);
            return Code.CREATED;
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return Code.UNAVAILABLE;}
    }
    /** Commit before PREPARE. A pending row means a possible request, not proof it reached the source. */
    public boolean recordAttempt(Subject s,Phase phase,UUID intent,long revision,PeerRetentionFenceProtocol.Scope scope){
        input(s,phase,intent,scope.fenceToken());validateScope(s,phase,scope);
        return tx.execute(status->{
            if(intents.checkPrepared(s,phase,intent,revision).code()!=CommerceRetentionIntents.CheckCode.CURRENT)return false;
            var rows=own(s,phase,intent,scope.fenceToken());
            if(rows.isEmpty() || blocked(rows.getFirst()) || Boolean.TRUE.equals(rows.getFirst().get("cleanup_only")) || rows.getFirst().get("finished_at")!=null || n(rows.getFirst(),"intent_revision")!=revision)return false;
            var old=jdbc.queryForList("SELECT binding_epoch,source_revision,release_confirmed_at FROM commerce_retention_observation_leases WHERE round_id=? AND source=?",scope.fenceToken(),scope.source().name());
            if(!old.isEmpty())return false; // Never resend PREPARE from a retained attempt, even after RELEASE.
            jdbc.update("INSERT INTO commerce_retention_observation_leases(round_id,source,binding_epoch,source_revision) VALUES(?,?,?,?)",
                scope.fenceToken(),scope.source().name(),scope.epoch(),scope.revision());return true;
        });
    }
    /** Acknowledgement recording is allowed after stop/retirement, but never after restore quarantine. */
    public boolean acknowledgeRelease(Subject s,Phase phase,UUID intent,PeerRetentionFenceProtocol.Command command,PeerRetentionFenceProtocol.Reply reply){
        return acknowledgeRelease(s,phase,intent,command,reply,null);
    }
    boolean acknowledgeRelease(Subject s,Phase phase,UUID intent,PeerRetentionFenceProtocol.Command command,PeerRetentionFenceProtocol.Reply reply,UUID recoveryToken){
        input(s,phase,intent,command.scope().fenceToken());validateScope(s,phase,command.scope());
        var valid=PeerRetentionFenceProtocol.validate(reply,command);
        if(command.action()!=PeerRetentionFenceProtocol.Action.RELEASE || valid.status()!=PeerRetentionFenceProtocol.Status.RELEASED)return false;
        return tx.execute(status->{
            var rows=own(s,phase,intent,command.scope().fenceToken());
            if(rows.isEmpty() || blocked(rows.getFirst()) || !canWrite(rows.getFirst(),recoveryToken))return false;
            recoveryContext(recoveryToken);
            var scope=command.scope();
            var leases=jdbc.queryForList("SELECT release_confirmed_at FROM commerce_retention_observation_leases WHERE round_id=? AND source=? AND binding_epoch=? AND source_revision=?",scope.fenceToken(),scope.source().name(),scope.epoch(),scope.revision());
            if(leases.isEmpty())return false;
            if(leases.getFirst().get("release_confirmed_at")!=null)return true;
            return jdbc.update("UPDATE commerce_retention_observation_leases SET release_confirmed_at=clock_timestamp() WHERE round_id=? AND source=? AND release_confirmed_at IS NULL",scope.fenceToken(),scope.source().name())==1;
        });
    }
    public boolean finish(Subject s,Phase phase,UUID intent,UUID round){
        return finish(s,phase,intent,round,null);
    }
    boolean finish(Subject s,Phase phase,UUID intent,UUID round,UUID recoveryToken){
        input(s,phase,intent,round);
        return tx.execute(status->{
            var rows=own(s,phase,intent,round);if(rows.isEmpty() || blocked(rows.getFirst()))return false;
            if(rows.getFirst().get("finished_at")!=null)return true;
            if(!canWrite(rows.getFirst(),recoveryToken))return false;
            recoveryContext(recoveryToken);
            return jdbc.update("UPDATE commerce_retention_observation_rounds SET finished_at=clock_timestamp(),recovery_token=NULL,recovery_until=NULL WHERE round_id=? AND NOT EXISTS(SELECT 1 FROM commerce_retention_observation_leases WHERE round_id=? AND release_confirmed_at IS NULL)",round,round)==1;
        });
    }
    /** Read-only inspection, including historical/stopped scopes. No transport, claim, retry or TTL clearance. */
    public Optional<Snapshot> inspect(Subject s,Phase phase,UUID intent,UUID round){
        input(s,phase,intent,round);
        return tx.execute(status->{
            var rows=own(s,phase,intent,round);if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
            var leases=new EnumMap<Participant,Lease>(Participant.class);
            for(var lease:jdbc.queryForList("SELECT source,binding_epoch,source_revision,release_confirmed_at FROM commerce_retention_observation_leases WHERE round_id=? ORDER BY source",round)){
                var source=Participant.valueOf((String)lease.get("source"));
                leases.put(source,new Lease(new PeerRetentionFenceProtocol.Scope(source,s.orderId(),s.chainGeneration(),s.orderVersion(),phase,
                    n(lease,"binding_epoch"),n(lease,"source_revision"),round),lease.get("release_confirmed_at")!=null));
            }
            return Optional.of(new Snapshot(round,intent,n(row,"intent_revision"),((Number)row.get("ordinal")).intValue(),row.get("finished_at")!=null,blocked(row),Boolean.TRUE.equals(row.get("cleanup_only")),((Number)row.get("recovery_attempts")).intValue(),leases));
        });
    }
    /** Explicit takeover; stop/cancel and retired generations may still be cleaned up. */
    public RecoveryClaim claimRecovery(Subject s,Phase phase,UUID intent,UUID round){
        input(s,phase,intent,round);UUID token=UUID.randomUUID();
        try{return tx.execute(status->{
            var rows=own(s,phase,intent,round);if(rows.isEmpty())return new RecoveryClaim(RecoveryCode.STALE,null);
            var row=rows.getFirst();
            if(blocked(row))return new RecoveryClaim(RecoveryCode.QUARANTINED,null);
            if(row.get("finished_at")!=null)return new RecoveryClaim(RecoveryCode.FINISHED,null);
            if(Boolean.TRUE.equals(row.get("recovery_live")))return new RecoveryClaim(RecoveryCode.BUSY,null);
            if(n(row,"recovery_attempts")>=8)return new RecoveryClaim(RecoveryCode.LIMIT_REACHED,null);
            jdbc.update("UPDATE commerce_retention_observation_rounds SET cleanup_only=TRUE,recovery_token=?,recovery_until=clock_timestamp()+interval '15 seconds',recovery_attempts=recovery_attempts+1 WHERE round_id=?",token,round);
            return new RecoveryClaim(RecoveryCode.CLAIMED,new RecoveryWork(s,phase,intent,round,token));
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return new RecoveryClaim(RecoveryCode.UNAVAILABLE,null);}
    }
    public boolean recoveryOwned(RecoveryWork work){
        input(work.subject(),work.phase(),work.intentId(),work.roundId());
        return tx.execute(status->{var rows=own(work.subject(),work.phase(),work.intentId(),work.roundId());return !rows.isEmpty() && !blocked(rows.getFirst()) && rows.getFirst().get("finished_at")==null && canWrite(rows.getFirst(),work.token());});
    }
    boolean yieldRecovery(RecoveryWork work){
        input(work.subject(),work.phase(),work.intentId(),work.roundId());
        return tx.execute(status->{
            var rows=own(work.subject(),work.phase(),work.intentId(),work.roundId());
            if(rows.isEmpty() || blocked(rows.getFirst()) || rows.getFirst().get("finished_at")!=null || !canWrite(rows.getFirst(),work.token()))return false;
            recoveryContext(work.token());return jdbc.update("UPDATE commerce_retention_observation_rounds SET recovery_token=NULL,recovery_until=NULL WHERE round_id=?",work.roundId())==1;
        });
    }
    /** Caller may retain this row lock through its final local assessment; never across RPC. */
    boolean activeWithinTransaction(Subject s,Phase phase,UUID intent,UUID round){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_ROUND_TRANSACTION_REQUIRED");
        var rows=own(s,phase,intent,round);return !rows.isEmpty() && !blocked(rows.getFirst()) && rows.getFirst().get("finished_at")==null && !Boolean.TRUE.equals(rows.getFirst().get("cleanup_only"));
    }
    public boolean active(Subject s,Phase phase,UUID intent,UUID round){
        input(s,phase,intent,round);return tx.execute(status->activeWithinTransaction(s,phase,intent,round));
    }
    private boolean canWrite(Map<String,Object> row,UUID token){
        return !Boolean.TRUE.equals(row.get("cleanup_only"))?token==null:
            token!=null && token.equals(row.get("recovery_token")) && Boolean.TRUE.equals(row.get("recovery_live"));
    }
    private void recoveryContext(UUID token){if(token!=null)jdbc.queryForObject("SELECT set_config('app.retention_recovery_token',?,TRUE)",String.class,token.toString());}
    private List<Map<String,Object>> own(Subject s,Phase phase,UUID intent,UUID round){
        return jdbc.queryForList("""
            SELECT r.*,i.restore_blocked AS intent_restore_blocked,
              (r.recovery_token IS NOT NULL AND r.recovery_until>clock_timestamp()) AS recovery_live FROM commerce_retention_observation_rounds r
            JOIN commerce_retention_intents i USING(intent_id)
            JOIN commerce_retention_jobs j ON j.order_id=i.order_id AND j.generation=i.generation AND j.phase=i.phase
            WHERE r.round_id=? AND i.intent_id=? AND i.order_id=? AND i.generation=? AND i.order_version=?
              AND i.phase=? AND i.policy=? AND coalesce(j.customer_id,0)=? FOR UPDATE OF r
            """,round,intent,s.orderId(),s.chainGeneration(),s.orderVersion(),phase.name(),POLICY,s.customerId());
    }
    private static void input(Subject s,Phase phase,UUID intent,UUID round){
        Objects.requireNonNull(s);Objects.requireNonNull(phase);Objects.requireNonNull(intent);Objects.requireNonNull(round);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_ROUND_CALLER_TRANSACTION");
    }
    private static void validateScope(Subject s,Phase phase,PeerRetentionFenceProtocol.Scope scope){
        if(!scope.valid() || scope.orderId()!=s.orderId() || !scope.generation().equals(s.chainGeneration())
                || scope.orderVersion()!=s.orderVersion() || scope.phase()!=phase)throw new IllegalArgumentException("Invalid observation scope");
    }
    private static boolean blocked(Map<String,Object> row){return Boolean.TRUE.equals(row.get("restore_blocked")) || Boolean.TRUE.equals(row.get("intent_restore_blocked"));}
    private static long n(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
}
