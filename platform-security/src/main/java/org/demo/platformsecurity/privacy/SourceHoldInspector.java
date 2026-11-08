package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;
import static org.demo.platformsecurity.privacy.SourceHoldInspectionProtocol.*;

/** Read-only observation serialized with source-local negative hold writers. */
public final class SourceHoldInspector {
    private final Participant own;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public SourceHoldInspector(Participant source,DataSource ds,PlatformTransactionManager manager){
        if(source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source))throw new IllegalArgumentException("Unsupported source hold inspector");
        own=source;jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }
    public Reply inspect(Command c){
        if(c==null || !c.valid() || c.delivery().source()!=own)throw new IllegalArgumentException("Invalid local hold inspection");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("SOURCE_HOLD_INSPECTION_CALLER_TRANSACTION");
        return tx.execute(status->{
            var d=c.delivery();jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,d.orderId());
            var rows=jdbc.queryForList("SELECT binding_epoch,source_revision,restore_blocked FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE",d.orderId());
            if(rows.isEmpty())return new Reply(c,Code.UNKNOWN_PURCHASE,Instant.now(),0,0);
            var state=rows.getFirst();long epoch=((Number)state.get("binding_epoch")).longValue(),revision=((Number)state.get("source_revision")).longValue();
            if(Boolean.TRUE.equals(state.get("restore_blocked")))return new Reply(c,Code.QUARANTINED,Instant.now(),epoch,revision);
            var holds=jdbc.queryForList("SELECT hold_id,order_id,guard,reason_code FROM commerce_retention_source_holds WHERE request_id=?",d.requestId());
            Code code;
            if(holds.isEmpty())code=Code.MISSING_HOLD;
            else{var hold=holds.getFirst();code=c.expectedHoldId().equals(hold.get("hold_id")) && d.orderId()==((Number)hold.get("order_id")).longValue() && d.guard().name().equals(hold.get("guard")) && d.reason().name().equals(hold.get("reason_code"))?Code.MATCH:Code.CONFLICT;}
            return new Reply(c,code,Instant.now(),epoch,revision);
        });
    }
}
