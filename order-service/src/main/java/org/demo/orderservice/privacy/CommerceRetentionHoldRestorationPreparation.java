package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Builds a scoped request from immutable intent SQL. It does not issue or approve case authorization. */
public final class CommerceRetentionHoldRestorationPreparation {
    public enum Code { COMMAND_PREPARED, UNKNOWN_INTENT, QUARANTINED, RECHECK_REQUIRED, UNAVAILABLE }
    public record Prepared(Code code,SourceHoldRestorationProtocol.Command command){
        public boolean caseAuthorizationVerified(){return false;}
        public boolean activationAllowed(){return false;}
    }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionHoldRestorationPreparation(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    public Prepared prepare(long order,UUID request,UUID ref,Participant source,UUID intent,UUID challenge,String authorization,SourceHoldRestorationProtocol.Mode mode){
        if(order<1 || request==null || ref==null || source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source) || intent==null || challenge==null || authorization==null || authorization.length()>4096 || mode==null)throw new IllegalArgumentException("Invalid restoration preparation scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_RESTORATION_PREPARATION_CALLER_TRANSACTION");
        try{return tx.execute(status->{
            var rows=jdbc.queryForList("SELECT guard,reason_code,restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,ref);
            if(rows.isEmpty())return new Prepared(Code.UNKNOWN_INTENT,null);var parent=rows.getFirst();
            boolean blocked=Boolean.TRUE.equals(parent.get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
            if(blocked)return new Prepared(Code.QUARANTINED,null);
            var intents=jdbc.queryForList("SELECT i.basis_probe_id,i.source_request_id,i.source_hold_id,r.binding_epoch,r.source_revision FROM commerce_retention_hold_repair_intents i JOIN commerce_retention_hold_probe_results r ON r.probe_id=i.basis_probe_id AND r.code='MISSING_AT_SOURCE' JOIN commerce_retention_hold_delivery_receipts d ON d.request_id=i.request_id AND d.source=i.source AND d.source_request_id=i.source_request_id AND d.source_hold_id=i.source_hold_id WHERE i.intent_id=? AND i.request_id=? AND i.source=?",intent,request,source.name());
            if(intents.size()!=1)return new Prepared(Code.UNKNOWN_INTENT,null);var row=intents.getFirst();
            if(mode==SourceHoldRestorationProtocol.Mode.RESTORE && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT retention_hold_repair_candidate(?)",Boolean.class,row.get("basis_probe_id"))))return new Prepared(Code.RECHECK_REQUIRED,null);
            var delivery=new SourceHoldDeliveryProtocol.Command(source,order,(UUID)row.get("source_request_id"),Guard.valueOf((String)parent.get("guard")),CommerceRetentionSourceHolds.Reason.valueOf((String)parent.get("reason_code")));
            return new Prepared(Code.COMMAND_PREPARED,new SourceHoldRestorationProtocol.Command(delivery,intent,ref,(UUID)row.get("source_hold_id"),((Number)row.get("binding_epoch")).longValue(),((Number)row.get("source_revision")).longValue(),challenge,authorization,mode));
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return new Prepared(Code.UNAVAILABLE,null);}
    }
}
