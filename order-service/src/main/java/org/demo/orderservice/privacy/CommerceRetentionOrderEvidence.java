package org.demo.orderservice.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Objects;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Trusted local Order adapter, unscheduled. No external statement becomes a proof. */
public final class CommerceRetentionOrderEvidence {
    public enum Code { RECORDED, STALE, QUARANTINED, PROCESS_ACTIVE, REVIEW_REQUIRED, MISSING_MILESTONE, MISSING_REDACTION }
    public record Result(Code code,CommerceRetentionJobs.Receipt receipt,State state) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionJobs jobs;
    public CommerceRetentionOrderEvidence(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);jobs=new CommerceRetentionJobs(ds,manager);}
    public Result collect(Subject subject,Phase phase){
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);
        return tx.execute(status->{
            var orders=jdbc.queryForList("SELECT id,customer_id,version,status,lifecycle_review_required FROM orders WHERE id=? FOR NO KEY UPDATE",subject.orderId());
            if(orders.isEmpty())return new Result(Code.STALE,null,State.UNKNOWN);var order=orders.getFirst();
            long customer=order.get("customer_id")==null?0:((Number)order.get("customer_id")).longValue();
            if(customer!=subject.customerId() || ((Number)order.get("version")).longValue()!=subject.orderVersion())return new Result(Code.STALE,null,State.UNKNOWN);
            var scopes=jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND phase=? AND policy=? AND current FOR UPDATE",subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase.name(),POLICY);
            if(scopes.isEmpty())return new Result(Code.STALE,null,State.UNKNOWN);
            var states=jdbc.queryForList("SELECT * FROM commerce_retention_order_state WHERE order_id=? FOR UPDATE",subject.orderId());
            if(states.isEmpty())return new Result(Code.MISSING_MILESTONE,null,State.UNKNOWN);var local=states.getFirst();
            if(Boolean.TRUE.equals(local.get("restore_blocked")))return new Result(Code.QUARANTINED,null,State.UNKNOWN);
            // Lock actual lifecycle rows, not only their last observed metadata.
            var sagas=jdbc.queryForList("SELECT state,processing_token,processing_until,next_attempt_at FROM order_sagas WHERE order_id=? FOR SHARE",subject.orderId());
            boolean review=Boolean.TRUE.equals(order.get("lifecycle_review_required")) || sagas.size()!=1;
            boolean active=!java.util.Set.of("COMPLETED","CANCELLED").contains(order.get("status"));
            boolean retry=false;
            if(sagas.size()==1){var saga=sagas.getFirst();retry=saga.get("processing_token")!=null || saga.get("processing_until")!=null || saga.get("next_attempt_at")!=null;
                active|=retry || !java.util.Set.of("COMPLETED","FAILED").contains(saga.get("state"));}
            State state=active?State.BLOCKED:State.UNKNOWN;Code code=active?Code.PROCESS_ACTIVE:review?Code.REVIEW_REQUIRED:Code.MISSING_MILESTONE;
            java.time.Instant reconciled=null;
            if(!active && !review && Boolean.TRUE.equals(jdbc.queryForObject("SELECT commerce_retention_order_ready(?)",Boolean.class,subject.orderId())) && local.get("reconciled_at") instanceof Timestamp time){
                if(phase==Phase.REDACTION){state=State.CONFIRMED;code=Code.RECORDED;reconciled=time.toInstant();}
                else code=Code.MISSING_REDACTION;
            }
            var receipt=jobs.record(new ChainProof(Participant.ORDER,subject,phase,POLICY,state,reconciled,null));
            if(receipt==CommerceRetentionJobs.Receipt.STALE || receipt==CommerceRetentionJobs.Receipt.CONFLICT)
                return new Result(Code.STALE,receipt,State.UNKNOWN);
            // A local active retry can block the global guard. Local absence
            // cannot certify the absence of retries/DLQ in the seven peers.
            if(retry)jobs.record(Guard.NO_ACTIVE_RETRY_OR_DLQ,new GuardProof(subject,phase,POLICY,State.BLOCKED));
            return new Result(code,receipt,state);
        });
    }
}
