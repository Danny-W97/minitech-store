package org.demo.orderservice.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Durable, unscheduled coordinator. BOUND means a known binding effect, never a positive proof. */
public final class CommerceRetentionBindings {
    public enum Source { RETURNS, PAYMENT, INVENTORY, PROMOTION, DELIVERY, BILLING, NOTIFICATION }
    public enum Preparation { PREPARED, IDEMPOTENT, STALE, PREDECESSOR_PENDING, QUARANTINED }
    public record Work(Subject subject, Phase phase, Source source, long expectedEpoch, UUID token) {
        public Work {
            Objects.requireNonNull(subject); Objects.requireNonNull(phase); Objects.requireNonNull(source); Objects.requireNonNull(token);
            if(expectedEpoch<0 || expectedEpoch==Long.MAX_VALUE) throw new IllegalArgumentException("Invalid binding epoch");
        }
    }
    public record Acknowledgement(long epoch, long revision, boolean invalidated) {
        public Acknowledgement {
            if(epoch<1 || revision<0) throw new IllegalArgumentException("Invalid binding acknowledgement");
        }
    }
    @FunctionalInterface public interface Transport {
        Acknowledgement bind(Source source, Subject subject, Phase phase, long expectedEpoch);
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public CommerceRetentionBindings(DataSource ds, PlatformTransactionManager manager) {
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }

    /** Derives every expected epoch only from retained receipts, never from a fresh remote read. */
    public Preparation prepare(Subject subject, Phase phase) {
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);
        return tx.execute(status->{
            var owners=jdbc.queryForList("SELECT id,customer_id,version FROM orders WHERE id=? FOR NO KEY UPDATE",subject.orderId());
            if(owners.isEmpty()) return Preparation.STALE;
            var owner=owners.getFirst();
            var rows=jdbc.queryForList("SELECT * FROM commerce_retention_jobs WHERE order_id=? AND generation=? FOR UPDATE",subject.orderId(),subject.chainGeneration());
            if(rows.isEmpty()) return Preparation.STALE;
            var job=rows.getFirst();
            if(!Boolean.TRUE.equals(job.get("current")) || !POLICY.equals(job.get("policy")) || !phase.name().equals(job.get("phase"))
                    || number(owner,"version")!=subject.orderVersion() || customer(owner)!=subject.customerId()
                    || number(job,"order_version")!=subject.orderVersion() || customer(job)!=subject.customerId()) return Preparation.STALE;
            if(count("SELECT count(*) FROM commerce_retention_bindings WHERE order_id=? AND restore_blocked",subject.orderId())>0) return Preparation.QUARANTINED;
            long existing=count("SELECT count(*) FROM commerce_retention_bindings WHERE order_id=? AND generation=?",subject.orderId(),subject.chainGeneration());
            if(existing!=0) {
                if(existing!=Source.values().length) throw new IllegalStateException("RETENTION_BINDING_INCOMPLETE");
                return Preparation.IDEMPOTENT;
            }
            if(count("SELECT count(*) FROM commerce_retention_bindings WHERE order_id=? AND state<>'BOUND'",subject.orderId())>0) return Preparation.PREDECESSOR_PENDING;
            for(var source:Source.values()) {
                var receipts=jdbc.queryForList("SELECT bound_epoch FROM commerce_retention_bindings WHERE order_id=? AND source=? ORDER BY expected_epoch DESC LIMIT 1",subject.orderId(),source.name());
                long epoch=receipts.isEmpty()?0:number(receipts.getFirst(),"bound_epoch");
                if(epoch==Long.MAX_VALUE) throw new IllegalStateException("RETENTION_BINDING_EPOCH_EXHAUSTED");
                jdbc.update("INSERT INTO commerce_retention_bindings(order_id,generation,source,order_version,phase,policy,expected_epoch) VALUES(?,?,?,?,?,?,?)",
                        subject.orderId(),subject.chainGeneration(),source.name(),subject.orderVersion(),phase.name(),POLICY,epoch);
            }
            return Preparation.PREPARED;
        });
    }

    /** Old commands may finish to resolve an unknown effect; newer commands remain blocked meanwhile. */
    public Optional<Work> claim() {
        return tx.execute(status->{
            jdbc.update("""
                    UPDATE commerce_retention_bindings SET state='FAILED',code='RETRY_LIMIT',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
                    WHERE (order_id,generation,source) IN (SELECT order_id,generation,source FROM commerce_retention_bindings
                    WHERE NOT restore_blocked AND state='LEASED' AND attempts=8 AND run_until<=clock_timestamp()
                    ORDER BY retry_at,order_id,source LIMIT 25 FOR UPDATE SKIP LOCKED)
                    """);
            var rows=jdbc.queryForList("""
                    SELECT * FROM commerce_retention_bindings WHERE NOT restore_blocked AND policy=? AND attempts<8
                    AND state IN ('PENDING','LEASED','FAILED') AND retry_at<=clock_timestamp()
                    AND (run_until IS NULL OR run_until<=clock_timestamp())
                    ORDER BY retry_at,order_id,source LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,POLICY);
            if(rows.isEmpty()) return Optional.empty();
            var row=rows.getFirst();var token=UUID.randomUUID();
            jdbc.update("UPDATE commerce_retention_bindings SET state='LEASED',attempts=attempts+1,run_token=?,run_until=clock_timestamp()+INTERVAL '15 seconds',updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND generation=? AND source=?",
                    token,row.get("order_id"),row.get("generation"),row.get("source"));
            // No customer identity is stored in or sent by binding commands.
            return Optional.of(new Work(new Subject(number(row,"order_id"),0,number(row,"order_version"),(UUID)row.get("generation")),
                    Phase.valueOf((String)row.get("phase")),Source.valueOf((String)row.get("source")),number(row,"expected_epoch"),token));
        });
    }
    public boolean acknowledge(Work work,Acknowledgement ack) {
        Objects.requireNonNull(work);Objects.requireNonNull(ack);
        if(ack.epoch()!=work.expectedEpoch()+1) return false;
        return Boolean.TRUE.equals(tx.execute(status->{
            // Same lock order as preparation, assessment and invalidation.
            jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR NO KEY UPDATE",work.subject().orderId());
            int saved=jdbc.update("""
                    UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=?,source_revision=?,invalidated=?,code=NULL,
                    run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
                    WHERE order_id=? AND generation=? AND source=? AND order_version=? AND phase=? AND policy=? AND expected_epoch=?
                    AND NOT restore_blocked AND state='LEASED' AND run_token=? AND run_until>clock_timestamp()
                    """,ack.epoch(),ack.revision(),ack.invalidated(),work.subject().orderId(),work.subject().chainGeneration(),work.source().name(),
                    work.subject().orderVersion(),work.phase().name(),POLICY,work.expectedEpoch(),work.token());
            if(saved==1 && ack.invalidated()) jdbc.update("UPDATE commerce_retention_jobs SET current=FALSE,state='STALE',code='SOURCE_INVALIDATED',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND generation=? AND phase=? AND order_version=? AND current",
                    work.subject().orderId(),work.subject().chainGeneration(),work.phase().name(),work.subject().orderVersion());
            return saved==1;
        }));
    }
    public boolean failed(Work work) {
        Objects.requireNonNull(work);
        return jdbc.update("""
                UPDATE commerce_retention_bindings SET state='FAILED',code=CASE WHEN attempts=8 THEN 'RETRY_LIMIT' ELSE 'TRANSPORT_FAILED' END,
                run_token=NULL,run_until=NULL,retry_at=clock_timestamp()+INTERVAL '30 seconds',updated_at=CURRENT_TIMESTAMP
                WHERE order_id=? AND generation=? AND source=? AND order_version=? AND phase=? AND policy=? AND expected_epoch=?
                AND NOT restore_blocked AND state='LEASED' AND run_token=? AND run_until>clock_timestamp()
                """,work.subject().orderId(),work.subject().chainGeneration(),work.source().name(),work.subject().orderVersion(),
                work.phase().name(),POLICY,work.expectedEpoch(),work.token())==1;
    }
    /** Exactly one durable command; transport runs after claim commit and before ACK transaction. */
    public boolean publishOne(Transport transport) {
        Objects.requireNonNull(transport);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("RETENTION_BINDING_CALLER_TRANSACTION");
        var next=claim();if(next.isEmpty())return false;var work=next.get();
        try {
            var ack=transport.bind(work.source(),work.subject(),work.phase(),work.expectedEpoch());
            if(ack==null || ack.epoch()!=work.expectedEpoch()+1) {failed(work);return true;}
            acknowledge(work,ack);
        } catch(RuntimeException failure) {failed(work);}
        return true;
    }
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static long customer(Map<String,Object> row){return row.get("customer_id")==null?0:number(row,"customer_id");}
}
