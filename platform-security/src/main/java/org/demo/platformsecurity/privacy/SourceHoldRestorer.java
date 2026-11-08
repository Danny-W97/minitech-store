package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;
import static org.demo.platformsecurity.privacy.SourceHoldRestorationProtocol.*;

/** Source-local CAS, original hold UUID, invalidation and durable receipt in one transaction. */
public final class SourceHoldRestorer {
    private final Participant own;private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final SourceHoldCaseAuthorization authority;
    public SourceHoldRestorer(Participant source,DataSource ds,PlatformTransactionManager manager,SourceHoldCaseAuthorization authority){
        if(source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source))throw new IllegalArgumentException("Unsupported source hold restorer");
        own=source;this.authority=Objects.requireNonNull(authority);jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }
    public Reply restore(Command c){
        if(c==null || !c.valid() || c.delivery().source()!=own)throw new IllegalArgumentException("Invalid local hold restoration");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("SOURCE_HOLD_RESTORATION_CALLER_TRANSACTION");
        final SourceHoldCaseAuthorization.Authorization authorization;
        try{authorization=authority.verify(c);}catch(IllegalArgumentException denied){return denied(c,Code.AUTHORIZATION_REQUIRED);}
        try{return tx.execute(status->{
            var d=c.delivery();jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,d.orderId());
            var states=jdbc.queryForList("SELECT binding_epoch,source_revision,restore_blocked FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE",d.orderId());
            if(states.isEmpty())return denied(c,Code.UNKNOWN_PURCHASE);var state=states.getFirst();
            if(Boolean.TRUE.equals(state.get("restore_blocked")))return denied(c,Code.QUARANTINED);
            var receipts=jdbc.queryForList("SELECT * FROM commerce_retention_hold_restoration_receipts WHERE intent_id=?",c.intentId());
            if(!receipts.isEmpty()){
                var r=receipts.getFirst();boolean same=scopeHash(c).equals(r.get("command_hash")) && authorization.id().equals(r.get("case_authorization_id")) && c.holdId().equals(r.get("hold_id")) && d.requestId().equals(r.get("source_request_id"));
                if(!same || !matchingHold(c))return denied(c,Code.CONFLICT);
                return reply(c,Code.REPLAYED,((Number)r.get("binding_epoch")).longValue(),((Number)r.get("source_revision")).longValue(),authorization.id());
            }
            if(c.mode()==Mode.REPLAY_ONLY)return denied(c,Code.REPLAY_UNAVAILABLE);
            if(!authority.active(authorization))return denied(c,Code.EXPIRED_AUTHORIZATION);
            long epoch=((Number)state.get("binding_epoch")).longValue(),revision=((Number)state.get("source_revision")).longValue();
            if(epoch!=c.bindingEpoch() || revision!=c.sourceRevision())return denied(c,Code.STALE_SOURCE);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_source_holds WHERE hold_id=? OR request_id=?)",Boolean.class,c.holdId(),d.requestId())))return denied(c,Code.CONFLICT);
            jdbc.update("INSERT INTO commerce_retention_source_holds(hold_id,order_id,guard,reason_code,request_id) VALUES(?,?,?,?,?)",c.holdId(),d.orderId(),d.guard().name(),d.reason().name(),d.requestId());
            long changed=jdbc.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=?",Long.class,d.orderId());
            jdbc.update("INSERT INTO commerce_retention_hold_restoration_receipts(intent_id,case_authorization_id,command_hash,order_id,source_request_id,hold_id,binding_epoch,source_revision) VALUES(?,?,?,?,?,?,?,?)",c.intentId(),authorization.id(),scopeHash(c),d.orderId(),d.requestId(),c.holdId(),epoch,changed);
            return reply(c,Code.RESTORED,epoch,changed,authorization.id());
        });}catch(org.springframework.dao.DataIntegrityViolationException conflict){return denied(c,Code.CONFLICT);}
    }
    private boolean matchingHold(Command c){var d=c.delivery();return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_source_holds WHERE hold_id=? AND order_id=? AND request_id=? AND guard=? AND reason_code=?)",Boolean.class,c.holdId(),d.orderId(),d.requestId(),d.guard().name(),d.reason().name()));}
    private static Reply denied(Command c,Code code){return reply(c,code,0,0,null);}
}
