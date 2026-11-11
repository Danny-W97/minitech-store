package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** One explicit bounded release-only pass. No discovery scan, scheduler or automatic retry. */
public final class CommerceRetentionRoundRecovery {
    public enum Code { SETTLED, RETRY_REQUIRED, OWNERSHIP_LOST, BUSY, FINISHED, LIMIT_REACHED, STALE, QUARANTINED, UNAVAILABLE }
    public record Result(Code code,UUID roundId,Map<Participant,Boolean> acknowledgements) {
        public Result { acknowledgements=Map.copyOf(acknowledgements); }
        public boolean activationAllowed(){return false;}
        public boolean executionTerminated(){return false;}
    }
    private final CommerceRetentionObservationRounds rounds;
    public CommerceRetentionRoundRecovery(DataSource ds,org.springframework.transaction.PlatformTransactionManager manager){rounds=new CommerceRetentionObservationRounds(ds,manager);}
    public Result recover(Subject s,Phase phase,UUID intent,UUID round,CommerceRetentionWorkflow.FenceTransport transport){
        Objects.requireNonNull(transport);var acknowledgements=new EnumMap<Participant,Boolean>(Participant.class);
        var claim=rounds.claimRecovery(s,phase,intent,round);
        if(claim.code()!=CommerceRetentionObservationRounds.RecoveryCode.CLAIMED)
            return new Result(Code.valueOf(claim.code().name()),round,acknowledgements);
        var work=claim.work();
        try{
            var inspected=rounds.inspect(s,phase,intent,round);
            if(inspected.isEmpty())return new Result(Code.OWNERSHIP_LOST,round,acknowledgements);
            var snapshot=inspected.get();
            if(!rounds.recoveryOwned(work))return new Result(Code.OWNERSHIP_LOST,round,acknowledgements);
            for(var participant:Participant.values()){
                var lease=snapshot.leases().get(participant);if(lease==null || lease.releaseAcknowledged())continue;
                if(!rounds.recoveryOwned(work))return new Result(Code.OWNERSHIP_LOST,round,acknowledgements);
                var command=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,lease.scope());
                PeerRetentionFenceProtocol.Reply reply=null;
                try{reply=PeerRetentionFenceProtocol.validate(transport.fence(command),command);}catch(RuntimeException unavailable){/* No retry; retain pending history. */}
                if(!rounds.recoveryOwned(work))return new Result(Code.OWNERSHIP_LOST,round,acknowledgements);
                boolean recorded=reply!=null && reply.status()==PeerRetentionFenceProtocol.Status.RELEASED
                    && rounds.acknowledgeRelease(s,phase,intent,command,reply,work.token());
                acknowledgements.put(participant,recorded);
            }
            if(!rounds.recoveryOwned(work))return new Result(Code.OWNERSHIP_LOST,round,acknowledgements);
            return new Result(rounds.finish(s,phase,intent,round,work.token())?Code.SETTLED:Code.RETRY_REQUIRED,round,acknowledgements);
        }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){
            return new Result(Code.UNAVAILABLE,round,acknowledgements);
        }finally{
            try{rounds.yieldRecovery(work);}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){/* Lease expires; no inferred acknowledgement. */}
        }
    }
}
