package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.InventoryRetentionProtocol;
import org.demo.platformsecurity.privacy.InventoryRetentionProtocol.Code;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.InventoryRetentionProtocol.*;

/** Read current Order expectations, HTTP after commit, revalidate before storing
 * an as-of-read snapshot. Not an erasure permission or a distributed write fence. */
public final class CommerceRetentionInventoryCollector {
    public enum Result { STORED, IDEMPOTENT, STALE, WAITING, UNAVAILABLE }
    @FunctionalInterface public interface Transport { Reply collect(Command command); }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionJobs jobs;
    public CommerceRetentionInventoryCollector(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);jobs=new CommerceRetentionJobs(ds,manager);}
    public Result collect(Subject subject,Phase phase,Transport transport){
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);Objects.requireNonNull(transport);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("INVENTORY_EVIDENCE_CALLER_TRANSACTION");
        Command command=tx.execute(status->expectation(subject,phase));if(command==null)return Result.WAITING;
        final Reply observed;
        try{observed=InventoryRetentionProtocol.validateReply(transport.collect(command),command);}
        catch(RuntimeException unavailable){return Result.UNAVAILABLE;}
        return tx.execute(status->{
            var current=expectation(subject,phase);if(!command.equals(current))return Result.STALE;
            if(observed.code()==Code.STALE || observed.code()==Code.QUARANTINED){jobs.invalidate(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase);return Result.STALE;}
            var receipt=jobs.record(new ChainProof(Participant.INVENTORY,subject,phase,POLICY,observed.state(),observed.reconciledAt(),null));
            return switch(receipt){case STORED->Result.STORED;case IDEMPOTENT->Result.IDEMPOTENT;default->Result.STALE;};
        });
    }
    private Command expectation(Subject subject,Phase phase){
        var owners=jdbc.queryForList("SELECT id,customer_id,version,status,inventory_reservation_id FROM orders WHERE id=? FOR NO KEY UPDATE",subject.orderId());
        if(owners.isEmpty())return null;var order=owners.getFirst();long customer=order.get("customer_id")==null?0:((Number)order.get("customer_id")).longValue();
        if(customer!=subject.customerId() || number(order,"version")!=subject.orderVersion())return null;
        if(jdbc.queryForList("SELECT 1 FROM commerce_retention_jobs WHERE order_id=? AND generation=? AND order_version=? AND phase=? AND policy=? AND current FOR UPDATE",subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase.name(),POLICY).isEmpty())return null;
        var bindings=jdbc.queryForList("SELECT bound_epoch,source_revision FROM commerce_retention_bindings WHERE order_id=? AND generation=? AND source='INVENTORY' AND order_version=? AND phase=? AND policy=? AND state='BOUND' AND NOT restore_blocked AND NOT invalidated FOR UPDATE",subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase.name(),POLICY);
        if(bindings.size()!=1 || order.get("inventory_reservation_id")==null || !Set.of("COMPLETED","CANCELLED").contains(order.get("status"))
            || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT commerce_retention_order_ready(?)",Boolean.class,subject.orderId())))return null;
        var binding=bindings.getFirst();long reservation=number(order,"inventory_reservation_id");if(reservation<1)return null;
        var rows=jdbc.queryForList("SELECT product_id,quantity FROM order_items WHERE order_id=? ORDER BY product_id,id LIMIT 101",subject.orderId());
        List<Line> lines=new ArrayList<>();for(var row:rows)lines.add(new Line(number(row,"product_id"),((Number)row.get("quantity")).intValue()));
        var c=new Command(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase,number(binding,"bound_epoch"),number(binding,"source_revision"),reservation,
            "COMPLETED".equals(order.get("status"))?Terminal.CONFIRMED:Terminal.RELEASED,lines);
        return c.valid()?c:null;
    }
    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
}
