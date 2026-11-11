package org.demo.orderservice.privacy;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.UUID;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Guard;
import static org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.*;

/** Bounded immutable history of a validated read. Never queried as current authority.
 * The caller holds the Order/job/binding snapshot locks and the surrounding transaction.
 * Only semantic scope/states are compared on retry; volatile challenges/times are not reused.
 */
final class CommerceRetentionSourceReceipts {
    enum Receipt { STORED, IDEMPOTENT, CONFLICT }
    private final JdbcTemplate jdbc;
    CommerceRetentionSourceReceipts(DataSource ds){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);}

    Receipt record(Command command,Reply reply){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_RECEIPT_TRANSACTION_REQUIRED");
        PeerRetentionGuardProtocol.validate(reply,command);
        if(reply.code()!=Code.OBSERVED)throw new IllegalArgumentException("Historical observation required");
        var scope=command.scope();String hash=fingerprint(command,reply);
        var previous=jdbc.queryForList("SELECT observation_hash FROM commerce_retention_source_receipts WHERE order_id=? AND generation=? AND phase=? AND source=?",
                scope.orderId(),scope.generation(),scope.phase().name(),scope.source().name());
        if(!previous.isEmpty())return hash.equals(previous.getFirst().get("observation_hash"))?Receipt.IDEMPOTENT:Receipt.CONFLICT;
        jdbc.update("""
                INSERT INTO commerce_retention_source_receipts(order_id,generation,phase,source,order_version,policy,
                binding_epoch,source_revision,receipt_id,observation_hash,case_state,retry_state,observed_at,hold_state)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,scope.orderId(),scope.generation(),scope.phase().name(),scope.source().name(),scope.orderVersion(),command.policy(),
                scope.epoch(),scope.revision(),UUID.randomUUID(),hash,
                reply.states().get(Guard.NO_OPEN_OR_REOPENED_CASE).name(),reply.states().get(Guard.NO_ACTIVE_RETRY_OR_DLQ).name(),Timestamp.from(reply.observedAt()),reply.states().get(Guard.NO_PRESERVATION_HOLD).name());
        return Receipt.STORED;
    }
    private static String fingerprint(Command c,Reply r){
        var s=c.scope();
        boolean held=r.states().get(Guard.NO_PRESERVATION_HOLD)==org.demo.platformsecurity.privacy.CommerceRetentionAssessment.State.BLOCKED;
        return Access.hash("A43/source-guard-observation/"+(held?"2":"1")+"\n"+s.source()+"\n"+s.orderId()+"\n"+s.generation()+"\n"+s.orderVersion()+"\n"
                +s.phase()+"\n"+s.epoch()+"\n"+s.revision()+"\n"+c.policy()+"\n"+r.code()+"\n"
                +r.states().get(Guard.NO_OPEN_OR_REOPENED_CASE)+"\n"+r.states().get(Guard.NO_ACTIVE_RETRY_OR_DLQ)+"\n"+(held?"BLOCKED\n":""));
    }
}
