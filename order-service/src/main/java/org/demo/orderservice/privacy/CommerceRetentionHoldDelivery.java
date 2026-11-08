package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Explicit one-source step; persisted attempts precede RPC. Receipts are historical
 * negative-write acknowledgements, not present-day global coverage or mutation authority. */
public final class CommerceRetentionHoldDelivery {
    public enum Code { RECORDED, ALREADY_RECORDED, RETRY_REQUIRED, UNKNOWN_REQUEST, QUARANTINED, LIMIT_REACHED, CONFLICT, UNAVAILABLE }
    @FunctionalInterface public interface Transport { SourceHoldDeliveryProtocol.Reply deliver(SourceHoldDeliveryProtocol.Command command); }
    public record Result(Code code,Participant source,UUID attemptId,UUID sourceHoldId){public boolean activationAllowed(){return false;}public boolean completeCaseCoverage(){return false;}}
    private record Frame(Code failure,SourceHoldDeliveryProtocol.Command command,UUID storedHold){}
    private record Work(Result result,SourceHoldDeliveryProtocol.Command command,UUID attempt){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public CommerceRetentionHoldDelivery(DataSource ds,PlatformTransactionManager manager){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    public Result deliver(long order,UUID request,UUID caseRef,Participant source,Transport remote){
        if(order<1 || request==null || caseRef==null || source==null || !CommerceRetentionSourceHolds.SOURCES.contains(source) || remote==null)throw new IllegalArgumentException("Invalid hold delivery scope");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("RETENTION_HOLD_DELIVERY_CALLER_TRANSACTION");
        final Work work;
        try{work=tx.execute(status->{
            var f=frame(order,request,caseRef,source);if(f.failure()!=null)return new Work(result(f.failure(),source,null,f.storedHold()),null,null);
            if(f.storedHold()!=null)return new Work(result(Code.ALREADY_RECORDED,source,null,f.storedHold()),null,null);
            int count=jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts WHERE request_id=? AND source=?",Integer.class,request,source.name());
            if(count>=8)return new Work(result(Code.LIMIT_REACHED,source,null,null),null,null);
            UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO commerce_retention_hold_delivery_attempts(request_id,source,attempt_no,attempt_id) VALUES(?,?,?,?)",request,source.name(),count+1,attempt);
            return new Work(null,f.command(),attempt);
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,source,null,null);}
        if(work.result()!=null)return work.result();
        // No root locks span transport, including the check immediately before it.
        try{var current=tx.execute(status->frame(order,request,caseRef,source));if(current.failure()!=null)return result(current.failure(),source,work.attempt(),null);
            if(current.storedHold()!=null)return result(Code.ALREADY_RECORDED,source,work.attempt(),current.storedHold());
        }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,source,work.attempt(),null);}
        SourceHoldDeliveryProtocol.Reply received=null;
        try{received=SourceHoldDeliveryProtocol.validate(remote.deliver(work.command()),work.command());}catch(RuntimeException unavailable){/* Unknown outcome stays pending; no automatic retry. */}
        final var reply=received;
        try{return tx.execute(status->{
            var current=frame(order,request,caseRef,source);if(current.failure()!=null)return result(current.failure(),source,work.attempt(),null);
            if(reply==null || reply.code()!=SourceHoldDeliveryProtocol.Code.RECORDED)return result(Code.RETRY_REQUIRED,source,work.attempt(),null);
            if(current.storedHold()!=null)return result(current.storedHold().equals(reply.holdId())?Code.ALREADY_RECORDED:Code.CONFLICT,source,work.attempt(),current.storedHold());
            jdbc.update("INSERT INTO commerce_retention_hold_delivery_receipts(request_id,source,source_request_id,source_hold_id,attempt_id) VALUES(?,?,?,?,?)",request,source.name(),work.command().requestId(),reply.holdId(),work.attempt());
            return result(Code.RECORDED,source,work.attempt(),reply.holdId());
        });}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){return result(Code.UNAVAILABLE,source,work.attempt(),null);}
    }
    private Frame frame(long order,UUID request,UUID caseRef,Participant source){
        var rows=jdbc.queryForList("SELECT * FROM commerce_retention_hold_requests WHERE request_id=? AND order_id=? AND case_reference=? FOR UPDATE",request,order,caseRef);
        if(rows.isEmpty())return new Frame(Code.UNKNOWN_REQUEST,null,null);var row=rows.getFirst();
        boolean blocked=Boolean.TRUE.equals(row.get("restore_blocked")) || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=?) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=?)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=? AND restore_blocked)",Boolean.class,order,order,order));
        if(blocked)return new Frame(Code.QUARANTINED,null,null);
        var plan=jdbc.queryForList("SELECT source_request_id FROM commerce_retention_hold_source_requests WHERE request_id=? AND source=?",request,source.name());
        if(plan.size()!=1)return new Frame(Code.UNKNOWN_REQUEST,null,null);
        var command=new SourceHoldDeliveryProtocol.Command(source,order,(UUID)plan.getFirst().get("source_request_id"),Guard.valueOf((String)row.get("guard")),CommerceRetentionSourceHolds.Reason.valueOf((String)row.get("reason_code")));
        var receipts=jdbc.queryForList("SELECT source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source=?",request,source.name());
        return new Frame(null,command,receipts.isEmpty()?null:(UUID)receipts.getFirst().get("source_hold_id"));
    }
    private static Result result(Code code,Participant source,UUID attempt,UUID hold){return new Result(code,source,attempt,hold);}
}
