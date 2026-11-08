package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;
import static org.demo.platformsecurity.privacy.SourceHoldDeliveryProtocol.*;

public final class SourceHoldDeliveryReceiver {
    private final Participant own;private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CommerceRetentionSourceHolds holds;
    public SourceHoldDeliveryReceiver(Participant source,DataSource ds,PlatformTransactionManager manager){own=source;holds=new CommerceRetentionSourceHolds(source,ds,manager);jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    public Reply deliver(Command c){
        if(c==null || !c.valid() || c.source()!=own)throw new IllegalArgumentException("Invalid local source hold delivery");
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("SOURCE_HOLD_DELIVERY_CALLER_TRANSACTION");
        try{return tx.execute(status->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,c.orderId());
            var scope=jdbc.queryForList("SELECT restore_blocked FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE",c.orderId());
            if(scope.isEmpty())return new Reply(c,Code.UNKNOWN_PURCHASE,null);
            if(Boolean.TRUE.equals(scope.getFirst().get("restore_blocked")))return new Reply(c,Code.QUARANTINED,null);
            return new Reply(c,Code.RECORDED,holds.place(c.orderId(),c.requestId(),c.guard(),c.reason()));
        });}catch(IllegalStateException conflict){
            if("RETENTION_SOURCE_HOLD_REQUEST_CONFLICT".equals(conflict.getMessage()))return new Reply(c,Code.CONFLICT,null);
            throw conflict;
        }
    }
}
