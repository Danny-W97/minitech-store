package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Targeted internal assessment workflow. No scheduler, API, clearance or cleanup.
 * Checkpoints are as-of-read protection, not a distributed business-write fence. */
public final class CommerceRetentionWorkflow {
    public enum Code { PREPARED, IDEMPOTENT, STALE, BLOCKED, PREDECESSOR_PENDING,
        WAITING_BINDINGS, WAITING_PROCESS, WAITING_DEPENDENCY, UNAVAILABLE,
        OBSERVED, OBSERVATIONS_COMPLETE, FIELD_SCOPE_REVIEWED, GLOBAL_GUARDS_REVIEWED, ASSESSED, REVALIDATED_ASSESSED, FENCED_ASSESSED, RETRY_REVALIDATION, NOT_DUE,
        INTENT_STALE, INTENT_STOPPED, INTENT_QUARANTINED, INTENT_REVALIDATED_ASSESSED, INTENT_FENCED_ASSESSED,
        INTENT_ROUND_PENDING, INTENT_ROUND_LIMIT, INTENT_ROUND_TAKEN_OVER }
    public record Prepared(Code code,Subject subject) {}
    public record Step(Code code,Participant participant,Result assessment) {
        public boolean activationAllowed(){return false;}
    }
    public record Participation(long bindingEpoch,long sourceRevision) {}
    public record IntentReview(Code code,UUID intentId,long intentRevision,Map<Participant,Participation> observations,Result assessment) {
        public IntentReview { observations=Map.copyOf(observations); }
        public boolean activationAllowed(){return false;}
        public boolean executionTerminated(){return false;}
    }
    public enum ReleaseStatus { CONFIRMED, UNCONFIRMED }
    public enum Cleanup { NOT_REQUIRED, RELEASED, UNCONFIRMED }
    /** Release diagnostics describe this round's token only; never executor termination. */
    public record IntentFenceReview(IntentReview review,Map<Participant,ReleaseStatus> releases,UUID roundId,boolean historyRecorded) {
        public IntentFenceReview { releases=Map.copyOf(releases); }
        public Cleanup cleanup(){return releases.isEmpty()?Cleanup.NOT_REQUIRED:
            releases.containsValue(ReleaseStatus.UNCONFIRMED)?Cleanup.UNCONFIRMED:Cleanup.RELEASED;}
        public boolean activationAllowed(){return false;}
        public boolean executionTerminated(){return false;}
    }
    private record IntentFrame(Code code,Round round,Map<Participant,Participation> scopes) {}
    private record Check(Code code,CommerceRetentionHolds.Snapshot holds) {}
    private record Round(Code code,CommerceRetentionHolds.Snapshot holds,long revision) {}
    private static final class RecordedRound { UUID id;boolean created,recorded=true; }
    private static final List<Participant> ORDER=List.of(Participant.ORDER,Participant.PAYMENT,Participant.INVENTORY,
        Participant.RETURNS,Participant.PROMOTION,Participant.DELIVERY,Participant.BILLING,Participant.NOTIFICATION);
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final CommerceRetentionJobs jobs;private final CommerceRetentionBindings bindings;
    private final CommerceRetentionHolds holds;private final CommerceRetentionOrderEvidence local;
    private final CommerceRetentionInventoryCollector inventory;private final CommerceRetentionPeerCollector peers;
    private final CommerceRetentionGlobalGuardEvidence globalGuards;
    private final CommerceRetentionObservationRounds observationRounds;
    public CommerceRetentionWorkflow(DataSource ds,PlatformTransactionManager manager){
        this(ds,manager,null);
    }
    public CommerceRetentionWorkflow(DataSource ds,PlatformTransactionManager manager,org.demo.platformsecurity.privacy.JournalReceiptVerifier journalVerifier){
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
        jobs=new CommerceRetentionJobs(ds,manager);bindings=new CommerceRetentionBindings(ds,manager);
        holds=new CommerceRetentionHolds(ds,manager);local=new CommerceRetentionOrderEvidence(ds,manager);
        inventory=new CommerceRetentionInventoryCollector(ds,manager);peers=new CommerceRetentionPeerCollector(ds,manager);
        globalGuards=new CommerceRetentionGlobalGuardEvidence(ds,manager,journalVerifier);
        observationRounds=new CommerceRetentionObservationRounds(ds,manager);
    }
    /** A desired generation is the retry key; an explicit predecessor prevents blind replacement.
     * Account identity comes exclusively from Order. Only REDACTION observations exist today. */
    public Prepared prepare(long order,long expectedVersion,UUID predecessor,UUID generation,Phase phase){
        outsideTransaction();redactionOnly(phase);
        if(order<1 || expectedVersion<0 || generation==null || generation.equals(predecessor))throw new IllegalArgumentException("Invalid retention preparation");
        return tx.execute(status->{
            var owners=jdbc.queryForList("SELECT id,customer_id,version,privacy_redacted FROM orders WHERE id=? FOR NO KEY UPDATE",order);
            if(owners.isEmpty() || n(owners.getFirst(),"version")!=expectedVersion)return new Prepared(Code.STALE,null);
            var o=owners.getFirst();var subject=new Subject(order,customer(o),expectedVersion,generation);
            var h=holds.snapshot(order,customer(o),phase);
            if(blocked(o) || blockedHistory(order) || h.quarantined() || !h.blockers().isEmpty())return new Prepared(Code.BLOCKED,null);
            var current=jdbc.queryForList("SELECT generation,order_version,customer_id,phase,policy,preparation_predecessor FROM commerce_retention_jobs WHERE order_id=? AND current FOR UPDATE",order);
            if(!current.isEmpty() && generation.equals(current.getFirst().get("generation"))){
                var j=current.getFirst();
                if(!Objects.equals(predecessor,j.get("preparation_predecessor")) || n(j,"order_version")!=expectedVersion || customer(j)!=subject.customerId() || !phase.name().equals(j.get("phase")) || !POLICY.equals(j.get("policy")))return new Prepared(Code.STALE,null);
                return new Prepared(Code.IDEMPOTENT,subject);
            }
            if(!jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? AND generation=?",order,generation).isEmpty())return new Prepared(Code.STALE,null);
            if(!current.isEmpty() && !Objects.equals(predecessor,current.getFirst().get("generation")))return new Prepared(Code.STALE,null);
            if(predecessor==null ? !jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? LIMIT 1",order).isEmpty()
                : jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? AND generation=?",order,predecessor).isEmpty())return new Prepared(Code.STALE,null);
            if(!current.isEmpty())jobs.invalidate(order,predecessor);
            jobs.prepareGeneration(order,phase,generation,predecessor);
            var prepared=bindings.prepare(subject,phase);
            if(prepared!=CommerceRetentionBindings.Preparation.PREPARED){
                status.setRollbackOnly();return new Prepared(prepared==CommerceRetentionBindings.Preparation.PREDECESSOR_PENDING?Code.PREDECESSOR_PENDING:Code.BLOCKED,null);
            }
            return new Prepared(Code.PREPARED,subject);
        });
    }
    /** At most one source call. HTTP occurs between committed checkpoints, never inside SQL TX.
     * Concurrent reads can repeat a source observation; immutable receipts resolve duplicates. */
    public Step observeNext(Subject subject,Phase phase,CommerceRetentionInventoryCollector.Transport inventoryTransport,
                            CommerceRetentionPeerCollector.Transport peerTransport){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);
        Objects.requireNonNull(inventoryTransport);Objects.requireNonNull(peerTransport);
        var before=tx.execute(status->check(subject,phase,true));
        if(before.code()!=null)return stop(subject,phase,before.code(),null);
        var seen=tx.execute(status->jdbc.queryForList("SELECT source FROM commerce_retention_evidence WHERE order_id=? AND generation=? AND phase=? AND kind='CHAIN' AND policy=?",String.class,subject.orderId(),subject.chainGeneration(),phase.name(),POLICY));
        var next=ORDER.stream().filter(p->!seen.contains(p.name())).findFirst();
        if(next.isEmpty()){
            var after=tx.execute(status->check(subject,phase,true));
            if(after.code()!=null)return stop(subject,phase,after.code(),null);
            return !before.holds().equals(after.holds())?stop(subject,phase,Code.STALE,null):new Step(Code.OBSERVATIONS_COMPLETE,null,null);
        }
        var participant=next.get();var result=observe(subject,phase,participant,inventoryTransport,peerTransport);
        var after=tx.execute(status->check(subject,phase,true));
        if(after.code()!=null)return stop(subject,phase,after.code(),participant);
        if(!before.holds().equals(after.holds()))return stop(subject,phase,Code.STALE,participant);
        return stop(subject,phase,result,participant);
    }
    private Code observe(Subject subject,Phase phase,Participant participant,CommerceRetentionInventoryCollector.Transport inventoryTransport,CommerceRetentionPeerCollector.Transport peerTransport){
        Code result;
        if(participant==Participant.ORDER){
            var r=local.collect(subject,phase);result=r.receipt()==CommerceRetentionJobs.Receipt.STORED || r.receipt()==CommerceRetentionJobs.Receipt.IDEMPOTENT?Code.OBSERVED:r.code()==CommerceRetentionOrderEvidence.Code.STALE?Code.STALE:Code.BLOCKED;
        }else if(participant==Participant.INVENTORY){
            result=switch(inventory.collect(subject,phase,inventoryTransport)){
                case STORED,IDEMPOTENT->Code.OBSERVED;case STALE->Code.STALE;case UNAVAILABLE->Code.UNAVAILABLE;case WAITING->Code.WAITING_DEPENDENCY;};
        }else result=switch(peers.collect(subject,phase,participant,peerTransport)){
            case STORED,IDEMPOTENT->Code.OBSERVED;case STALE->Code.STALE;case UNAVAILABLE->Code.UNAVAILABLE;case WAITING->Code.WAITING_DEPENDENCY;};
        return result;
    }

    /** Explicit complete read round: existing eight observations only. At most
     * seven remote calls, outside SQL TX. Sequential reads are NOT a global
     * snapshot/write fence; the result cannot authorize a data mutation. */
    public Step revalidateAndAssess(Subject subject,Phase phase,CommerceRetentionInventoryCollector.Transport inventoryTransport,
                                    CommerceRetentionPeerCollector.Transport peerTransport){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);
        Objects.requireNonNull(inventoryTransport);Objects.requireNonNull(peerTransport);
        var before=tx.execute(status->round(subject,phase));
        if(before.code()!=null)return stop(subject,phase,before.code(),null);
        for(var participant:ORDER){
            var observed=observe(subject,phase,participant,inventoryTransport,peerTransport);
            if(observed!=Code.OBSERVED)return stop(subject,phase,observed,participant);
            var after=tx.execute(status->round(subject,phase,false));
            if(after.code()!=null)return stop(subject,phase,after.code(),participant);
            if(before.revision()!=after.revision() || !before.holds().equals(after.holds()))return new Step(Code.RETRY_REVALIDATION,participant,null);
        }
        var work=jobs.claimRevalidated(subject,phase,before.revision());
        if(work.isEmpty())return new Step(Code.NOT_DUE,null,null);
        Optional<Result> result;
        try{result=jobs.assess(work.get());}catch(RuntimeException failure){jobs.failed(work.get());throw failure;}
        var after=tx.execute(status->round(subject,phase,false));
        if(after.code()!=null)return stop(subject,phase,after.code(),null);
        if(before.revision()!=after.revision() || !before.holds().equals(after.holds()))return new Step(Code.RETRY_REVALIDATION,null,null);
        return result.map(r->new Step(Code.REVALIDATED_ASSESSED,null,r)).orElseGet(()->new Step(Code.NOT_DUE,null,null));
    }

    /** Fresh eight-source assessment bound to an unchanged preparation. No authorization or execution. */
    public IntentReview revalidateIntentAndAssess(Subject subject,Phase phase,UUID intentId,long intentRevision,
            CommerceRetentionInventoryCollector.Transport inventoryTransport,CommerceRetentionPeerCollector.Transport peerTransport){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);Objects.requireNonNull(intentId);
        Objects.requireNonNull(inventoryTransport);Objects.requireNonNull(peerTransport);
        if(intentRevision<0)throw new IllegalArgumentException("Invalid intent revision");
        var seen=new EnumMap<Participant,Participation>(Participant.class);
        try{
            var baseline=tx.execute(status->intentFrame(subject,phase,intentId,intentRevision,true));
            if(baseline.code()!=null)return intentReview(baseline.code(),intentId,intentRevision,seen,null);
            for(var participant:ORDER){
                var before=tx.execute(status->intentFrame(subject,phase,intentId,intentRevision,false));
                var changed=changedIntentFrame(baseline,before);
                if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                var observed=observe(subject,phase,participant,inventoryTransport,peerTransport);
                var after=tx.execute(status->intentFrame(subject,phase,intentId,intentRevision,false));
                changed=changedIntentFrame(baseline,after);
                if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                if(observed!=Code.OBSERVED)return intentReview(observed,intentId,intentRevision,seen,null);
                seen.put(participant,baseline.scopes().get(participant));
            }
            // Stop/cancel cannot enter between the final check, lease acquisition and SQL assessment.
            var assessed=tx.execute(status->{
                var current=intentFrame(subject,phase,intentId,intentRevision,false);
                var changed=changedIntentFrame(baseline,current);
                if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                var work=jobs.claimRevalidated(subject,phase,baseline.round().revision());
                if(work.isEmpty())return intentReview(Code.NOT_DUE,intentId,intentRevision,seen,null);
                var result=jobs.assess(work.get());
                return intentReview(result.isPresent()?Code.INTENT_REVALIDATED_ASSESSED:Code.INTENT_STALE,
                        intentId,intentRevision,seen,result.orElse(null));
            });
            return assessed; // As of this commit; any later stop remains effective for subsequent work.
        }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){
            return intentReview(Code.UNAVAILABLE,intentId,intentRevision,seen,null);
        }
    }
    private IntentFrame intentFrame(Subject s,Phase phase,UUID id,long revision,boolean requireDue){
        var prepared=new CommerceRetentionIntents(jdbc.getDataSource(),tx.getTransactionManager()).checkPrepared(s,phase,id,revision);
        Code code=switch(prepared.code()){
            case CURRENT->null;case STALE->Code.INTENT_STALE;case STOPPED->Code.INTENT_STOPPED;
            case BLOCKED->Code.BLOCKED;case QUARANTINED->Code.INTENT_QUARANTINED;};
        if(code!=null)return new IntentFrame(code,null,Map.of());
        var r=round(s,phase,requireDue);if(r.code()!=null)return new IntentFrame(r.code(),r,Map.of());
        var scopes=new EnumMap<Participant,Participation>(Participant.class);
        var localState=jdbc.queryForList("SELECT process_revision FROM commerce_retention_order_state WHERE order_id=?",s.orderId());
        if(localState.size()!=1)return new IntentFrame(Code.WAITING_DEPENDENCY,r,Map.of());
        scopes.put(Participant.ORDER,new Participation(0,n(localState.getFirst(),"process_revision")));
        for(var row:jdbc.queryForList("SELECT source,bound_epoch,source_revision FROM commerce_retention_bindings WHERE order_id=? AND generation=? AND order_version=? AND phase=? AND policy=? AND state='BOUND' AND NOT restore_blocked AND NOT invalidated ORDER BY source FOR SHARE",s.orderId(),s.chainGeneration(),s.orderVersion(),phase.name(),POLICY)){
            scopes.put(Participant.valueOf((String)row.get("source")),new Participation(n(row,"bound_epoch"),n(row,"source_revision")));
        }
        if(!scopes.keySet().equals(EnumSet.allOf(Participant.class)))return new IntentFrame(Code.WAITING_BINDINGS,r,Map.of());
        return new IntentFrame(null,r,Map.copyOf(scopes));
    }
    private static Code changedIntentFrame(IntentFrame before,IntentFrame after){
        if(after.code()!=null)return after.code();
        return before.round().revision()!=after.round().revision() || !before.round().holds().equals(after.round().holds())
                || !before.scopes().equals(after.scopes())?Code.INTENT_STALE:null;
    }
    private static IntentReview intentReview(Code code,UUID id,long revision,Map<Participant,Participation> seen,Result result){return new IntentReview(code,id,revision,seen,result);}

    @FunctionalInterface
    public interface FenceTransport {
        PeerRetentionFenceProtocol.Reply fence(PeerRetentionFenceProtocol.Command c);
    }

    /** Intent-bound observation leases only. No scheduler, execution permission or automatic retry.
     * Every attempted PREPARE is released, including a possibly committed request with a lost reply. */
    public IntentFenceReview fenceIntentAndAssess(Subject subject,Phase phase,UUID intentId,long intentRevision,
            CommerceRetentionInventoryCollector.Transport inventoryTransport,CommerceRetentionPeerCollector.Transport peerTransport,
            FenceTransport fenceTransport){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);Objects.requireNonNull(intentId);
        Objects.requireNonNull(inventoryTransport);Objects.requireNonNull(peerTransport);Objects.requireNonNull(fenceTransport);
        if(intentRevision<0)throw new IllegalArgumentException("Invalid intent revision");
        var attempted=new ArrayList<PeerRetentionFenceProtocol.Scope>();
        var releases=new EnumMap<Participant,ReleaseStatus>(Participant.class);
        var round=new RecordedRound();
        IntentReview review;
        try{
            review=reviewWithIntentFences(subject,phase,intentId,intentRevision,inventoryTransport,peerTransport,fenceTransport,attempted,round);
        }finally{
            // Original scope/token, even after stop, binding retirement or quarantine. Never adopt a newer lease.
            for(var scope:attempted){
                var release=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,scope);
                ReleaseStatus released=ReleaseStatus.UNCONFIRMED;
                try{
                    var reply=PeerRetentionFenceProtocol.validate(fenceTransport.fence(release),release);
                    if(reply.status()==PeerRetentionFenceProtocol.Status.RELEASED){
                        released=ReleaseStatus.CONFIRMED;
                        try{if(!observationRounds.acknowledgeRelease(subject,phase,intentId,release,reply))round.recorded=false;}
                        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){round.recorded=false;}
                    }
                }catch(RuntimeException unavailable){/* A TTL is a fallback, not a release acknowledgement. */}
                releases.put(scope.source(),released);
            }
            if(round.created){
                try{if(!observationRounds.finish(subject,phase,intentId,round.id) && releases.values().stream().allMatch(r->r==ReleaseStatus.CONFIRMED))round.recorded=false;}
                catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){round.recorded=false;}
            }
        }
        return new IntentFenceReview(review,releases,round.id,round.created && round.recorded);
    }
    private IntentReview reviewWithIntentFences(Subject subject,Phase phase,UUID intentId,long intentRevision,
            CommerceRetentionInventoryCollector.Transport inventoryTransport,CommerceRetentionPeerCollector.Transport peerTransport,
            FenceTransport transport,List<PeerRetentionFenceProtocol.Scope> attempted,RecordedRound round){
        var seen=new EnumMap<Participant,Participation>(Participant.class);
        var leases=new EnumMap<Participant,PeerRetentionFenceProtocol.Reply>(Participant.class);
        try{
            var baseline=tx.execute(status->intentFrame(subject,phase,intentId,intentRevision,true));
            if(baseline.code()!=null)return intentReview(baseline.code(),intentId,intentRevision,seen,null);
            for(var participant:ORDER){
                var changed=checkIntentFrame(subject,phase,intentId,intentRevision,baseline);
                if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                var observed=observe(subject,phase,participant,inventoryTransport,peerTransport);
                changed=checkIntentFrame(subject,phase,intentId,intentRevision,baseline);
                if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                if(observed!=Code.OBSERVED)return intentReview(observed,intentId,intentRevision,seen,null);
                seen.put(participant,baseline.scopes().get(participant));
            }
            UUID token=UUID.randomUUID();
            round.id=token;
            var registered=observationRounds.begin(subject,phase,intentId,intentRevision,token);
            if(registered!=CommerceRetentionObservationRounds.Code.CREATED){
                return intentReview(switch(registered){case PENDING_ROUND->Code.INTENT_ROUND_PENDING;case LIMIT_REACHED->Code.INTENT_ROUND_LIMIT;
                    case STALE->Code.INTENT_STALE;default->Code.UNAVAILABLE;},intentId,intentRevision,seen,null);
            }
            round.created=true;
            for(var action:List.of(PeerRetentionFenceProtocol.Action.PREPARE,PeerRetentionFenceProtocol.Action.VERIFY)){
                for(var participant:ORDER){
                    if(participant==Participant.ORDER)continue;
                    var changed=checkIntentFrame(subject,phase,intentId,intentRevision,baseline);
                    if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                    if(!observationRounds.active(subject,phase,intentId,token))return intentReview(Code.INTENT_ROUND_TAKEN_OVER,intentId,intentRevision,seen,null);
                    if(!leasesLive(leases,java.time.Instant.now()))return intentReview(Code.RETRY_REVALIDATION,intentId,intentRevision,seen,null);
                    var binding=baseline.scopes().get(participant);
                    var scope=new PeerRetentionFenceProtocol.Scope(participant,subject.orderId(),subject.chainGeneration(),
                            subject.orderVersion(),phase,binding.bindingEpoch(),binding.sourceRevision(),token);
                    var command=new PeerRetentionFenceProtocol.Command(action,scope);
                    if(action==PeerRetentionFenceProtocol.Action.PREPARE){
                        if(!observationRounds.recordAttempt(subject,phase,intentId,intentRevision,scope))return intentReview(
                            observationRounds.active(subject,phase,intentId,token)?Code.INTENT_STALE:Code.INTENT_ROUND_TAKEN_OVER,intentId,intentRevision,seen,null);
                        attempted.add(scope);
                    }
                    if(!observationRounds.active(subject,phase,intentId,token))return intentReview(Code.INTENT_ROUND_TAKEN_OVER,intentId,intentRevision,seen,null);
                    PeerRetentionFenceProtocol.Reply reply=null;
                    try{reply=PeerRetentionFenceProtocol.validate(transport.fence(command),command);}
                    catch(RuntimeException unavailable){/* Recheck stop/scope even after an uncertain RPC outcome. */}
                    changed=checkIntentFrame(subject,phase,intentId,intentRevision,baseline);
                    if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                    if(!observationRounds.active(subject,phase,intentId,token))return intentReview(Code.INTENT_ROUND_TAKEN_OVER,intentId,intentRevision,seen,null);
                    if(reply==null)return intentReview(Code.UNAVAILABLE,intentId,intentRevision,seen,null);
                    if(reply.status()!=PeerRetentionFenceProtocol.Status.FENCED)return intentReview(Code.STALE,intentId,intentRevision,seen,null);
                    // VERIFY may report a later observation time, but cannot renew the prepared lease.
                    if(action==PeerRetentionFenceProtocol.Action.VERIFY && !leases.get(participant).expiresAt().equals(reply.expiresAt()))
                        return intentReview(Code.UNAVAILABLE,intentId,intentRevision,seen,null);
                    leases.put(participant,reply);
                }
            }
            return tx.execute(status->{
                var changed=changedIntentFrame(baseline,intentFrame(subject,phase,intentId,intentRevision,false));
                if(changed!=null)return intentReview(changed,intentId,intentRevision,seen,null);
                if(!observationRounds.activeWithinTransaction(subject,phase,intentId,token))return intentReview(Code.INTENT_ROUND_TAKEN_OVER,intentId,intentRevision,seen,null);
                if(!leasesLiveAtDatabaseClock(leases))return intentReview(Code.RETRY_REVALIDATION,intentId,intentRevision,seen,null);
                var work=jobs.claimRevalidated(subject,phase,baseline.round().revision());
                if(work.isEmpty())return intentReview(Code.NOT_DUE,intentId,intentRevision,seen,null);
                var result=jobs.assess(work.get());
                if(!leasesLiveAtDatabaseClock(leases)){
                    status.setRollbackOnly();return intentReview(Code.RETRY_REVALIDATION,intentId,intentRevision,seen,null);
                }
                return intentReview(result.isPresent()?Code.INTENT_FENCED_ASSESSED:Code.INTENT_STALE,intentId,intentRevision,seen,result.orElse(null));
            });
        }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){
            round.recorded=false;
            return intentReview(Code.UNAVAILABLE,intentId,intentRevision,seen,null);
        }
    }
    private Code checkIntentFrame(Subject s,Phase phase,UUID id,long revision,IntentFrame baseline){
        return changedIntentFrame(baseline,tx.execute(status->intentFrame(s,phase,id,revision,false)));
    }
    private static boolean leasesLive(Map<Participant,PeerRetentionFenceProtocol.Reply> leases,java.time.Instant now){
        return leases.values().stream().allMatch(reply->reply.expiresAt().isAfter(now));
    }
    private boolean leasesLiveAtDatabaseClock(Map<Participant,PeerRetentionFenceProtocol.Reply> leases){
        return leases.size()==7 && leasesLive(leases,java.time.Instant.now())
                && leasesLive(leases,jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant());
    }

    /** Two-phase distributed write fence round across peer sources. Prepares and verifies
     * live fences, confirming that no peer has undergone a concurrent or late write before assessment. */
    public Step fenceAndAssess(Subject subject,Phase phase,CommerceRetentionInventoryCollector.Transport inventoryTransport,
                               CommerceRetentionPeerCollector.Transport peerTransport,FenceTransport fenceTransport){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);
        Objects.requireNonNull(inventoryTransport);Objects.requireNonNull(peerTransport);Objects.requireNonNull(fenceTransport);
        var before=tx.execute(status->round(subject,phase));
        if(before.code()!=null)return stop(subject,phase,before.code(),null);
        for(var participant:ORDER){
            var observed=observe(subject,phase,participant,inventoryTransport,peerTransport);
            if(observed!=Code.OBSERVED)return stop(subject,phase,observed,participant);
            var after=tx.execute(status->round(subject,phase,false));
            if(after.code()!=null)return stop(subject,phase,after.code(),participant);
            if(before.revision()!=after.revision() || !before.holds().equals(after.holds()))return new Step(Code.RETRY_REVALIDATION,participant,null);
        }
        var peerBindings=tx.execute(status->jdbc.queryForList(
                "SELECT source,bound_epoch,source_revision FROM commerce_retention_bindings WHERE order_id=? AND generation=? AND phase=? AND state='BOUND'",
                subject.orderId(),subject.chainGeneration(),phase.name()));
        if(peerBindings==null || peerBindings.size()!=7)return stop(subject,phase,Code.STALE,null);
        Map<Participant,long[]> scopes=new EnumMap<>(Participant.class);
        for(var row:peerBindings){
            String srcName=(String)row.get("source");
            Participant p=Participant.valueOf(srcName);
            scopes.put(p,new long[]{n(row,"bound_epoch"),n(row,"source_revision")});
        }
        UUID fenceToken=UUID.randomUUID();
        List<Participant> fenced=new ArrayList<>();
        // Phase 1: Prepare Fences on all peers
        for(var entry:scopes.entrySet()){
            Participant p=entry.getKey();long[] er=entry.getValue();
            var cmd=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.PREPARE,
                    new PeerRetentionFenceProtocol.Scope(p,subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase,er[0],er[1],fenceToken));
            PeerRetentionFenceProtocol.Reply reply;
            try{reply=PeerRetentionFenceProtocol.validate(fenceTransport.fence(cmd),cmd);}catch(RuntimeException err){
                releaseFences(subject,phase,fenced,scopes,fenceToken,fenceTransport);
                return stop(subject,phase,Code.UNAVAILABLE,p);
            }
            if(reply==null || reply.status()!=PeerRetentionFenceProtocol.Status.FENCED){
                releaseFences(subject,phase,fenced,scopes,fenceToken,fenceTransport);
                return stop(subject,phase,Code.STALE,p);
            }
            fenced.add(p);
        }
        // Phase 2: Verify Fences (checks live peer database state after all fences are active)
        for(var p:fenced){
            long[] er=scopes.get(p);
            var cmd=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.VERIFY,
                    new PeerRetentionFenceProtocol.Scope(p,subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase,er[0],er[1],fenceToken));
            PeerRetentionFenceProtocol.Reply reply;
            try{reply=PeerRetentionFenceProtocol.validate(fenceTransport.fence(cmd),cmd);}catch(RuntimeException err){
                releaseFences(subject,phase,fenced,scopes,fenceToken,fenceTransport);
                return stop(subject,phase,Code.UNAVAILABLE,p);
            }
            if(reply==null || reply.status()!=PeerRetentionFenceProtocol.Status.FENCED){
                releaseFences(subject,phase,fenced,scopes,fenceToken,fenceTransport);
                return stop(subject,phase,Code.STALE,p);
            }
        }
        // Phase 3: Claim and Assess under unbroken distributed fences
        var work=jobs.claimRevalidated(subject,phase,before.revision());
        if(work.isEmpty()){
            releaseFences(subject,phase,fenced,scopes,fenceToken,fenceTransport);
            return new Step(Code.NOT_DUE,null,null);
        }
        Optional<Result> result;
        try{result=jobs.assess(work.get());}catch(RuntimeException failure){
            jobs.failed(work.get());
            releaseFences(subject,phase,fenced,scopes,fenceToken,fenceTransport);
            throw failure;
        }
        releaseFences(subject,phase,fenced,scopes,fenceToken,fenceTransport);
        var after=tx.execute(status->round(subject,phase,false));
        if(after.code()!=null)return stop(subject,phase,after.code(),null);
        if(before.revision()!=after.revision() || !before.holds().equals(after.holds()))return new Step(Code.RETRY_REVALIDATION,null,null);
        return result.map(r->new Step(Code.FENCED_ASSESSED,null,r)).orElseGet(()->new Step(Code.NOT_DUE,null,null));
    }
    private void releaseFences(Subject subject,Phase phase,List<Participant> fenced,Map<Participant,long[]> scopes,UUID token,FenceTransport transport){
        for(var p:fenced){
            long[] er=scopes.get(p);if(er==null)continue;
            try{transport.fence(new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,
                    new PeerRetentionFenceProtocol.Scope(p,subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase,er[0],er[1],token)));}
            catch(RuntimeException ignored){}
        }
    }
    private Round round(Subject s,Phase phase){return round(s,phase,true);}
    private Round round(Subject s,Phase phase,boolean requireDue){
        var current=check(s,phase,true);if(current.code()!=null)return new Round(current.code(),current.holds(),-1);
        var job=jdbc.queryForMap("SELECT proof_revision,(attempts<8 AND (run_until IS NULL OR run_until<=clock_timestamp())) AS available,(state IN ('DUE_FOR_REVIEW','BLOCKED','REVIEW') OR next_attempt_at<=clock_timestamp()) AS due FROM commerce_retention_jobs WHERE order_id=? AND generation=?",s.orderId(),s.chainGeneration());
        long revision=n(job,"proof_revision");
        if(!Boolean.TRUE.equals(job.get("available")) || requireDue && !Boolean.TRUE.equals(job.get("due")))return new Round(Code.NOT_DUE,current.holds(),revision);
        int count=jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE order_id=? AND generation=? AND phase=? AND policy=? AND kind='CHAIN'",Integer.class,s.orderId(),s.chainGeneration(),phase.name(),POLICY);
        return new Round(count==8?null:Code.WAITING_DEPENDENCY,current.holds(),revision);
    }

    /** Review only the packaged field plan, leaving every other guard unresolved. */
    public Step reviewFieldScope(Subject subject,Phase phase){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);
        var before=tx.execute(status->check(subject,phase,false));
        if(before.code()!=null)return stop(subject,phase,before.code(),null);
        var r=new CommerceRetentionFieldScopeEvidence(jdbc.getDataSource(),tx.getTransactionManager()).collect(subject,phase);
        var after=tx.execute(status->check(subject,phase,false));
        if(after.code()!=null)return stop(subject,phase,after.code(),null);
        if(!before.holds().equals(after.holds()))return stop(subject,phase,Code.STALE,null);
        return stop(subject,phase,switch(r.code()){
            case RECORDED->Code.FIELD_SCOPE_REVIEWED;case STALE->Code.STALE;case BLOCKED->Code.BLOCKED;case UNAVAILABLE->Code.UNAVAILABLE;},null);
    }

    /** Registers preparation only; neither observations nor an intent authorize execution. */
    public CommerceRetentionIntents.Result prepareIntent(Subject subject,Phase phase,UUID intentId){
        outsideTransaction();redactionOnly(phase);
        return new CommerceRetentionIntents(jdbc.getDataSource(),tx.getTransactionManager()).prepare(subject,phase,intentId);
    }
    public CommerceRetentionIntents.Result requestIntentStop(Subject subject,Phase phase,UUID intentId,long expectedRevision,UUID requestId){
        outsideTransaction();redactionOnly(phase);
        return new CommerceRetentionIntents(jdbc.getDataSource(),tx.getTransactionManager()).requestStop(subject,phase,intentId,expectedRevision,requestId);
    }
    public CommerceRetentionIntents.Result cancelUnstartedIntent(Subject subject,Phase phase,UUID intentId,long expectedRevision,UUID requestId){
        outsideTransaction();redactionOnly(phase);
        return new CommerceRetentionIntents(jdbc.getDataSource(),tx.getTransactionManager()).cancelUnstarted(subject,phase,intentId,expectedRevision,requestId);
    }

    /** Observes one bound source; absence of a local blocker never establishes global clearance. */
    public Step reviewSourceGuards(Subject subject,Phase phase,Participant source,CommerceRetentionSourceGuardCollector.Source remote){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);Objects.requireNonNull(remote);
        try{return reviewCurrentSourceGuards(subject,phase,source,remote);}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return new Step(Code.UNAVAILABLE,source,null);}
    }
    private Step reviewCurrentSourceGuards(Subject subject,Phase phase,Participant source,CommerceRetentionSourceGuardCollector.Source remote){
        var before=tx.execute(status->check(subject,phase,false));
        if(before.code()!=null)return stop(subject,phase,before.code(),source);
        var result=new CommerceRetentionSourceGuardCollector(jdbc.getDataSource(),tx.getTransactionManager()).collect(subject,phase,source,remote);
        var after=tx.execute(status->check(subject,phase,false));
        if(after.code()!=null)return stop(subject,phase,after.code(),source);
        if(!before.holds().equals(after.holds()))return stop(subject,phase,Code.STALE,source);
        return stop(subject,phase,switch(result){case WAITING_PROOF->Code.WAITING_DEPENDENCY;case BLOCKED->Code.BLOCKED;case STALE->Code.STALE;case UNAVAILABLE->Code.UNAVAILABLE;},source);
    }

    /** Legacy global inputs remain negative-only until complete policy/head evidence exists. */
    public Step reviewGlobalGuards(Subject subject,Phase phase,CommerceRetentionGlobalGuardEvidence.GuardInputs inputs){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);Objects.requireNonNull(inputs);
        var before=tx.execute(status->check(subject,phase,false));
        if(before.code()!=null)return stop(subject,phase,before.code(),null);
        var r=globalGuards.collect(subject,phase,inputs);
        var after=tx.execute(status->check(subject,phase,false));
        if(after.code()!=null)return stop(subject,phase,after.code(),null);
        if(!before.holds().equals(after.holds()))return stop(subject,phase,Code.STALE,null);
        Code code=switch(r.code()){
            case RECORDED->Code.GLOBAL_GUARDS_REVIEWED;case BLOCKED->Code.BLOCKED;
            case STALE->Code.STALE;case WAITING_PROOF->Code.WAITING_DEPENDENCY;case UNAVAILABLE->Code.UNAVAILABLE;};
        return stop(subject,phase,code,null);
    }

    /** Exactly this subject may be leased; missing global guards remain missing. */
    public Step assessOne(Subject subject,Phase phase){
        outsideTransaction();redactionOnly(phase);Objects.requireNonNull(subject);
        var before=tx.execute(status->check(subject,phase,false));
        if(before.code()!=null)return stop(subject,phase,before.code(),null);
        var work=jobs.claim(subject,phase);if(work.isEmpty())return new Step(Code.NOT_DUE,null,null);
        Optional<Result> result;
        try{result=jobs.assess(work.get());}catch(RuntimeException failure){jobs.failed(work.get());throw failure;}
        var after=tx.execute(status->check(subject,phase,false));
        if(after.code()!=null)return stop(subject,phase,after.code(),null);
        if(!before.holds().equals(after.holds()))return stop(subject,phase,Code.STALE,null);
        return result.map(r->new Step(Code.ASSESSED,null,r)).orElseGet(()->new Step(Code.STALE,null,null));
    }
    private Check check(Subject s,Phase phase,boolean requireReady){
        var rows=jdbc.queryForList("SELECT id,customer_id,version,privacy_redacted FROM orders WHERE id=? FOR NO KEY UPDATE",s.orderId());
        if(rows.isEmpty() || customer(rows.getFirst())!=s.customerId() || n(rows.getFirst(),"version")!=s.orderVersion())return new Check(Code.STALE,null);
        if(jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND coalesce(customer_id,0)=? AND phase=? AND policy=? AND current FOR UPDATE",s.orderId(),s.chainGeneration(),s.orderVersion(),s.customerId(),phase.name(),POLICY).isEmpty())return new Check(Code.STALE,null);
        var h=holds.snapshot(s.orderId(),s.customerId(),phase);
        if(blocked(rows.getFirst()) || blockedHistory(s.orderId()) || h.quarantined() || !h.blockers().isEmpty())return new Check(Code.BLOCKED,h);
        var b=jdbc.queryForList("SELECT source,state,restore_blocked,invalidated FROM commerce_retention_bindings WHERE order_id=? AND generation=? AND order_version=? AND phase=? AND policy=? ORDER BY source FOR SHARE",s.orderId(),s.chainGeneration(),s.orderVersion(),phase.name(),POLICY);
        if(b.stream().anyMatch(r->Boolean.TRUE.equals(r.get("restore_blocked")) || Boolean.TRUE.equals(r.get("invalidated"))))return new Check(Code.BLOCKED,h);
        if(b.size()!=7 || b.stream().anyMatch(r->!"BOUND".equals(r.get("state"))))return new Check(Code.WAITING_BINDINGS,h);
        if(requireReady && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT commerce_retention_order_ready(?)",Boolean.class,s.orderId())))return new Check(Code.WAITING_PROCESS,h);
        return new Check(null,h);
    }
    private boolean blocked(Map<String,Object> o){
        return Boolean.TRUE.equals(o.get("privacy_redacted")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=?) OR EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=?)",Boolean.class,n(o,"id"),customer(o)));
    }
    private boolean blockedHistory(long order){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked) OR EXISTS(SELECT 1 FROM commerce_retention_bindings WHERE order_id=? AND restore_blocked)",Boolean.class,order,order));}
    private Step stop(Subject subject,Phase phase,Code code,Participant participant){
        if(code==Code.BLOCKED || code==Code.STALE)tx.executeWithoutResult(status->{
            var owners=jdbc.queryForList("SELECT customer_id,version FROM orders WHERE id=? FOR NO KEY UPDATE",subject.orderId());
            if(!owners.isEmpty() && customer(owners.getFirst())==subject.customerId() && n(owners.getFirst(),"version")==subject.orderVersion())
                jobs.invalidate(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase);
        });
        return new Step(code,participant,null);
    }
    private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_WORKFLOW_CALLER_TRANSACTION");}
    private static void redactionOnly(Phase phase){if(phase!=Phase.REDACTION)throw new IllegalArgumentException("Retention workflow supports redaction assessment only");}
    private static long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
    private static long customer(Map<String,Object> r){return r.get("customer_id")==null?0:n(r,"customer_id");}
}
