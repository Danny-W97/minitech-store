package org.demo.inventoryservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Phase;
import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.State;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Local observation only. The trusted Order bridge must obtain expectations
 * from its locked current order and bind this observation to the account/policy.
 * No caller proof, ChainProof, global guard, absence proof or runtime scheduling. */
public final class InventoryRetentionEvidence {
    public enum Terminal { CONFIRMED, RELEASED }
    public enum Code { OBSERVED, STALE, QUARANTINED, ACTIVE, REVIEW_REQUIRED, MISSING_MILESTONE, MISSING_REDACTION }
    public record Scope(long orderId, UUID generation, long orderVersion, Phase phase, long epoch, long revision) {
        public Scope {
            Objects.requireNonNull(generation);Objects.requireNonNull(phase);
            if(orderId<1 || orderVersion<0 || epoch<1 || revision<0)throw new IllegalArgumentException("Invalid inventory evidence scope");
        }
    }
    public record Line(long productId,int quantity) {
        public Line { if(productId<1 || quantity<1)throw new IllegalArgumentException("Invalid inventory evidence line"); }
    }
    public record Expected(long reservationId,Terminal status,List<Line> lines) {
        public Expected {
            Objects.requireNonNull(status);lines=List.copyOf(lines);
            if(reservationId<1 || lines.isEmpty() || lines.size()>100 || lines.stream().map(Line::productId).distinct().count()!=lines.size())
                throw new IllegalArgumentException("Invalid inventory evidence expectation");
        }
    }
    public record Observation(Scope scope,State state,Code code,Instant localReconciledAt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public InventoryRetentionEvidence(DataSource ds,PlatformTransactionManager manager) {
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }
    public Observation collect(Scope scope,Expected expected) {
        Objects.requireNonNull(scope);Objects.requireNonNull(expected);
        return tx.execute(status->{
            // All reservation/line writers acquire this same lock in their BEFORE
            // trigger. MVCC reads avoid a parent-row/advisory lock inversion.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,scope.orderId());
            var bindings=jdbc.queryForList("SELECT * FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE",scope.orderId());
            if(bindings.isEmpty())return result(scope,State.UNKNOWN,Code.STALE,null);
            var binding=bindings.getFirst();
            if(Boolean.TRUE.equals(binding.get("restore_blocked")))return result(scope,State.UNKNOWN,Code.QUARANTINED,null);
            if(!scope.generation().equals(binding.get("generation")) || scope.orderVersion()!=number(binding,"order_version")
                || !scope.phase().name().equals(binding.get("phase")) || scope.epoch()!=number(binding,"binding_epoch")
                || scope.revision()!=number(binding,"source_revision") || Boolean.TRUE.equals(binding.get("invalidated")))
                return result(scope,State.UNKNOWN,Code.STALE,null);
            var reservations=jdbc.queryForList("SELECT id,status FROM inventory_reservations WHERE order_id=? LIMIT 2",scope.orderId());
            if(reservations.size()!=1)return result(scope,State.UNKNOWN,Code.REVIEW_REQUIRED,null);
            var reservation=reservations.getFirst();
            if("RESERVED".equals(reservation.get("status")))return result(scope,State.BLOCKED,Code.ACTIVE,null);
            if(expected.reservationId()!=number(reservation,"id") || !expected.status().name().equals(reservation.get("status")))
                return result(scope,State.UNKNOWN,Code.REVIEW_REQUIRED,null);
            var rows=jdbc.queryForList("SELECT product_id,quantity FROM inventory_reservation_items WHERE reservation_id=? ORDER BY product_id,id LIMIT 101",expected.reservationId());
            var wanted=new HashMap<Long,Integer>();expected.lines().forEach(line->wanted.put(line.productId(),line.quantity()));
            var actual=new HashMap<Long,Integer>();
            for(var row:rows)if(actual.put(number(row,"product_id"),((Number)row.get("quantity")).intValue())!=null)
                return result(scope,State.UNKNOWN,Code.REVIEW_REQUIRED,null);
            if(rows.size()>100 || !actual.equals(wanted))return result(scope,State.UNKNOWN,Code.REVIEW_REQUIRED,null);
            if(!(binding.get("local_reconciled_at") instanceof Timestamp clock))return result(scope,State.UNKNOWN,Code.MISSING_MILESTONE,null);
            if(scope.phase()!=Phase.REDACTION)return result(scope,State.UNKNOWN,Code.MISSING_REDACTION,null);
            return result(scope,State.CONFIRMED,Code.OBSERVED,clock.toInstant());
        });
    }
    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private static Observation result(Scope scope,State state,Code code,Instant time){return new Observation(scope,state,code,time);}
}
