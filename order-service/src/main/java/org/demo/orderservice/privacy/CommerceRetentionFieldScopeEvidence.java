package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionFieldScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** One reviewed field-plan guard; not global preservation or erasure clearance.
 * Internal, unscheduled, no caller-supplied manifest, hash or confirmation flag. */
public final class CommerceRetentionFieldScopeEvidence {
    public enum Code { RECORDED, STALE, BLOCKED, UNAVAILABLE }
    public record Result(Code code,CommerceRetentionJobs.Receipt receipt,String definitionHash) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final CommerceRetentionJobs jobs;private final CommerceRetentionHolds holds;
    public CommerceRetentionFieldScopeEvidence(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);jobs=new CommerceRetentionJobs(ds,manager);holds=new CommerceRetentionHolds(ds,manager);}
    public Result collect(Subject subject,Phase phase){
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);
        final String digest;try{digest=CommerceRetentionFieldScope.verifiedDigest();}catch(IllegalStateException unavailable){return new Result(Code.UNAVAILABLE,null,null);}
        return tx.execute(status->{
            var owners=jdbc.queryForList("SELECT customer_id,version,privacy_redacted FROM orders WHERE id=? FOR NO KEY UPDATE",subject.orderId());
            if(owners.isEmpty())return new Result(Code.STALE,null,null);var o=owners.getFirst();
            if(customer(o)!=subject.customerId() || n(o,"version")!=subject.orderVersion())return new Result(Code.STALE,null,null);
            if(jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND coalesce(customer_id,0)=? AND phase=? AND policy=? AND current FOR UPDATE",subject.orderId(),subject.chainGeneration(),subject.orderVersion(),subject.customerId(),phase.name(),POLICY).isEmpty())return new Result(Code.STALE,null,null);
            var h=holds.snapshot(subject.orderId(),subject.customerId(),phase);
            boolean fenced=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=?)
                OR EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=?)
                OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)
                OR EXISTS(SELECT 1 FROM commerce_retention_bindings WHERE order_id=? AND restore_blocked)
                """,Boolean.class,subject.customerId(),subject.orderId(),subject.orderId(),subject.orderId()));
            if(fenced || Boolean.TRUE.equals(o.get("privacy_redacted")) || h.quarantined() || !h.blockers().isEmpty())return new Result(Code.BLOCKED,null,null);
            var receipt=jobs.recordReviewedFieldScope(subject,phase,digest);
            return receipt==CommerceRetentionJobs.Receipt.STORED || receipt==CommerceRetentionJobs.Receipt.IDEMPOTENT
                ?new Result(Code.RECORDED,receipt,digest):new Result(Code.STALE,receipt,null);
        });
    }
    private static long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
    private static long customer(Map<String,Object> r){return r.get("customer_id")==null?0:n(r,"customer_id");}
}
