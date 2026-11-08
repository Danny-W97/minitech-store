package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Explicit one-source diagnostic; no repair, historical overwrite or mutation authority. */
public final class CommerceRetentionHoldReconciliation {
    public enum Code { MATCH_AS_OF_READ, MISSING_AT_SOURCE, SOURCE_CONFLICT, SOURCE_UNKNOWN, SOURCE_QUARANTINED,
        ROOT_QUARANTINED, NO_HISTORICAL_RECEIPT, UNKNOWN_REQUEST, UNAVAILABLE }
    @FunctionalInterface public interface Transport { SourceHoldInspectionProtocol.Reply inspect(SourceHoldInspectionProtocol.Command c); }
    public record Result(Code code,Participant source,Instant observedAt,long bindingEpoch,long sourceRevision){
        public boolean completeCaseCoverage(){return false;}
        public boolean activationAllowed(){return false;}
        public boolean repairPerformed(){return false;}
        public boolean persistentReconciliationRecorded(){return false;}
    }
    private record Frame(Code failure,SourceHoldDeliveryProtocol.Command delivery,UUID hold){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionHoldReconciliation(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    public Result inspect(long order,UUID request,UUID caseRef,Participant source,Transport remote){
        return inspect(order,request,caseRef,source,UUID.randomUUID(),remote);
    }
    Result inspect(long order,UUID request,UUID caseRef,Participant source,UUID challenge,Transport remote){
        if(order<1 || request==null || caseRef==null || source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source) || challenge==null || remote==null)throw new IllegalArgumentException("Invalid hold reconciliation scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_RECONCILIATION_CALLER_TRANSACTION");
        final Frame before;
        try{before=tx.execute(status->frame(order,request,caseRef,source));}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return failed(Code.UNAVAILABLE,source);}
        if(before.failure()!=null)return failed(before.failure(),source);
        var command=new SourceHoldInspectionProtocol.Command(before.delivery(),before.hold(),challenge);
        SourceHoldInspectionProtocol.Reply received=null;
        try{received=SourceHoldInspectionProtocol.validate(remote.inspect(command),command);}catch(RuntimeException unknown){/* Do not reinterpret failure as absence. */}
        final var reply=received;
        try{return tx.execute(status->{
            var after=frame(order,request,caseRef,source);
            if(after.failure()!=null)return failed(after.failure(),source);
            if(!before.equals(after) || reply==null)return failed(Code.UNAVAILABLE,source);
            try{SourceHoldInspectionProtocol.validate(reply,command);}catch(IllegalArgumentException stale){return failed(Code.UNAVAILABLE,source);}
            Code code=switch(reply.code()){
                case MATCH->Code.MATCH_AS_OF_READ;case MISSING_HOLD->Code.MISSING_AT_SOURCE;
                case CONFLICT->Code.SOURCE_CONFLICT;case UNKNOWN_PURCHASE->Code.SOURCE_UNKNOWN;case QUARANTINED->Code.SOURCE_QUARANTINED;
            };
            return new Result(code,source,reply.observedAt(),reply.bindingEpoch(),reply.sourceRevision());
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return failed(Code.UNAVAILABLE,source);}
    }
    private Frame frame(long order,UUID request,UUID caseRef,Participant source){
        var rows=jdbc.queryForList("SELECT guard,reason_code,restore_blocked FROM commerce_retention_hold_requests WHERE order_id=? AND request_id=? AND case_reference=? FOR UPDATE",order,request,caseRef);
        if(rows.isEmpty())return new Frame(Code.UNKNOWN_REQUEST,null,null);var parent=rows.getFirst();
        boolean quarantined=Boolean.TRUE.equals(parent.get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
        if(quarantined)return new Frame(Code.ROOT_QUARANTINED,null,null);
        var receipt=jdbc.queryForList("SELECT p.source_request_id,r.source_hold_id FROM commerce_retention_hold_source_requests p JOIN commerce_retention_hold_delivery_receipts r ON r.request_id=p.request_id AND r.source=p.source AND r.source_request_id=p.source_request_id WHERE p.request_id=? AND p.source=?",request,source.name());
        if(receipt.size()!=1)return new Frame(Code.NO_HISTORICAL_RECEIPT,null,null);
        var row=receipt.getFirst();var delivery=new SourceHoldDeliveryProtocol.Command(source,order,(UUID)row.get("source_request_id"),Guard.valueOf((String)parent.get("guard")),CommerceRetentionSourceHolds.Reason.valueOf((String)parent.get("reason_code")));
        return new Frame(null,delivery,(UUID)row.get("source_hold_id"));
    }
    private static Result failed(Code code,Participant source){return new Result(code,source,null,0,0);}
}
