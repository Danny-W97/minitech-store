package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.PeerRetentionProtocol;
import org.demo.platformsecurity.privacy.PeerRetentionProtocol.Code;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.math.*;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionProtocol.*;

/** HTTP outside SQL transactions, immutable source receipts and current-owner revalidation. */
public final class CommerceRetentionPeerCollector {
    public enum Result { STORED, IDEMPOTENT, WAITING, STALE, UNAVAILABLE }
    @FunctionalInterface public interface Transport { Reply collect(Command c); }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionJobs jobs;
    public CommerceRetentionPeerCollector(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);jobs=new CommerceRetentionJobs(ds,manager);}
    public Result collect(Subject subject,Phase phase,Participant source,Transport transport){
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);Objects.requireNonNull(transport);if(!PEERS.contains(source))throw new IllegalArgumentException("Unsupported peer");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("PEER_EVIDENCE_CALLER_TRANSACTION");
        var c=tx.execute(status->expectation(subject,phase,source));if(c==null)return Result.WAITING;
        final Reply observed;try{observed=PeerRetentionProtocol.validate(transport.collect(c),c);}catch(RuntimeException unavailable){return Result.UNAVAILABLE;}
        return tx.execute(status->{
            if(!c.equals(expectation(subject,phase,source)))return Result.STALE;
            if(observed.code()==Code.STALE || observed.code()==Code.QUARANTINED){jobs.invalidate(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase);return Result.STALE;}
            if(source==Participant.PAYMENT && observed.state()==State.CONFIRMED){
                var previous=jdbc.queryForList("SELECT binding_epoch,source_revision,event_hash,event_count,expectation_hash FROM commerce_retention_payment_evidence WHERE order_id=? AND generation=? AND phase=?",subject.orderId(),subject.chainGeneration(),phase.name());
                if(!previous.isEmpty()){var p=previous.getFirst();if(n(p,"binding_epoch")!=c.scope().epoch() || n(p,"source_revision")!=c.scope().revision() || !observed.eventHash().equals(p.get("event_hash")) || observed.eventCount()!=n(p,"event_count") || !c.expectationHash().equals(p.get("expectation_hash"))){jobs.invalidate(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase);return Result.STALE;}}
            }
            var receipt=jobs.record(new ChainProof(source,subject,phase,POLICY,observed.state(),observed.reconciledAt(),null));
            if(receipt==CommerceRetentionJobs.Receipt.CONFLICT || receipt==CommerceRetentionJobs.Receipt.STALE)return Result.STALE;
            if(source==Participant.PAYMENT && observed.state()==State.CONFIRMED)jdbc.update("INSERT INTO commerce_retention_payment_evidence(order_id,generation,phase,binding_epoch,source_revision,event_hash,event_count,expectation_hash,policy) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",subject.orderId(),subject.chainGeneration(),phase.name(),c.scope().epoch(),c.scope().revision(),observed.eventHash(),observed.eventCount(),c.expectationHash(),POLICY);
            return receipt==CommerceRetentionJobs.Receipt.STORED?Result.STORED:Result.IDEMPOTENT;
        });
    }
    private Command expectation(Subject subject,Phase phase,Participant source){
        var rows=jdbc.queryForList("SELECT * FROM orders WHERE id=? FOR NO KEY UPDATE",subject.orderId());if(rows.isEmpty())return null;var o=rows.getFirst();long customer=o.get("customer_id")==null?0:n(o,"customer_id");
        if(customer!=subject.customerId() || n(o,"version")!=subject.orderVersion())return null;
        if(jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND phase=? AND policy=? AND current FOR UPDATE",subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase.name(),POLICY).isEmpty())return null;
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT commerce_retention_order_ready(?)",Boolean.class,subject.orderId())))return null;
        var bs=jdbc.queryForList("SELECT bound_epoch,source_revision FROM commerce_retention_bindings WHERE order_id=? AND generation=? AND source=? AND order_version=? AND phase=? AND policy=? AND state='BOUND' AND NOT restore_blocked AND NOT invalidated FOR UPDATE",subject.orderId(),subject.chainGeneration(),source.name(),subject.orderVersion(),phase.name(),POLICY);if(bs.size()!=1)return null;
        String eventHash=null;int eventCount=0;
        if(source==Participant.BILLING || source==Participant.NOTIFICATION){
            var p=jdbc.queryForList("""
                SELECT p.event_hash,p.event_count FROM commerce_retention_payment_evidence p
                JOIN commerce_retention_evidence e ON e.order_id=p.order_id AND e.generation=p.generation AND e.phase=p.phase
                JOIN commerce_retention_bindings b ON b.order_id=p.order_id AND b.generation=p.generation AND b.phase=p.phase AND b.source='PAYMENT'
                WHERE p.order_id=? AND p.generation=? AND p.phase=? AND p.policy=? AND e.kind='CHAIN' AND e.source='PAYMENT'
                AND e.state='CONFIRMED' AND e.policy=p.policy AND b.policy=p.policy AND b.state='BOUND' AND NOT b.restore_blocked AND NOT b.invalidated
                AND b.bound_epoch=p.binding_epoch AND b.source_revision=p.source_revision
                """,subject.orderId(),subject.chainGeneration(),phase.name(),POLICY);
            if(p.size()!=1)return null;eventHash=(String)p.getFirst().get("event_hash");eventCount=((Number)p.getFirst().get("event_count")).intValue();
        }
        BigDecimal amount=(BigDecimal)o.get("total_price"),discount=(BigDecimal)o.get("discount_amount"),delivery=(BigDecimal)o.get("delivery_cost");
        long owner=source==Participant.RETURNS || source==Participant.BILLING?customer:0;
        var facts=new Facts(owner,Objects.toString(o.get("status"),""),money(amount),money(amount.subtract(delivery).add(discount)),money(discount),(String)o.get("promotion_code"),(String)o.get("delivery_tracking_number"),(String)o.get("delivery_status"),o.get("delivery_revision")==null?-1:n(o,"delivery_revision"),Boolean.TRUE.equals(o.get("invoice_requested")),eventHash,eventCount);
        var b=bs.getFirst();var c=new Command(new Scope(source,subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase,n(b,"bound_epoch"),n(b,"source_revision")),facts);return c.valid()?c:null;
    }
    private static String money(BigDecimal v){return v.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
    private static long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
}
