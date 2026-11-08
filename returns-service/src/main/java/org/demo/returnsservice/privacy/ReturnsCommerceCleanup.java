package org.demo.returnsservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class ReturnsCommerceCleanup implements CommerceCleanupAdapter,FinancePreparationAdapter {
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;
    public ReturnsCommerceCleanup(PrivacyFenceStore fences,CommerceOrderSource source){this.fences=fences;this.source=source;}
    public Batch cleanup(long customer,UUID request,long cursor){return process(customer,request,cursor,false,-1);}
    public FinancePreparationAdapter.Result prepare(long customer,UUID request,long order,long version,String cursor){
        fences.underFence(customer,request,jdbc->true);
        var row=FinancePreparationAdapter.exact(source,customer,request,order,version);
        if(!row.eligibility().equals("ELIGIBLE"))return FinancePreparationAdapter.Result.waitFor(row.eligibility(),"0-0");
        var result=process(customer,request,order-1,true,version);
        return result.outcome().equals("WAIT")?FinancePreparationAdapter.Result.waitFor(result.code(),"0-0"):FinancePreparationAdapter.Result.prepared();
    }
    private Batch process(long customer,UUID request,long cursor,boolean prepare,long expectedVersion){
        fences.underFence(customer,request,jdbc->true);var next=source.next(customer,request,cursor);
        if(next.isEmpty())return fences.underFence(customer,request,jdbc->Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM return_requests WHERE customer_id=? AND privacy_redacted=FALSE)",Boolean.class,customer))?Batch.waitFor(cursor,"RETURN_REVIEW_REQUIRED"):Batch.end(cursor));
        var order=next.get();if(!order.eligibility().equals("ELIGIBLE"))return Batch.waitFor(cursor,order.eligibility());
        return fences.underFence(customer,request,jdbc->{
            CommerceLocks.lock(jdbc,order.orderId());
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM return_requests WHERE order_id=? AND (customer_id<>? OR request_hash IS NULL OR order_verified_at IS NULL OR status='COMPLETED'))",Boolean.class,order.orderId(),customer)))return Batch.waitFor(cursor,"RETURN_REVIEW_REQUIRED");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM return_requests WHERE order_id=? AND status<>'REJECTED')",Boolean.class,order.orderId())))return Batch.waitFor(cursor,"WAITING_RETURN_PROCESS");
            if(prepare&&order.orderVersion()!=expectedVersion)return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!prepare&&!order.financeCleared())return Batch.waitFor(cursor,"WAITING_A43_36");
            CommerceLocks.retain(jdbc,order.orderId(),customer,request);
            if(prepare)return new Batch(order.orderId(),"MORE","IDENTIFIERS_RETAINED");
            int headers=jdbc.update("UPDATE return_requests SET customer_email=NULL,reason=NULL,description=NULL,moderation_note=NULL,privacy_redacted=TRUE,updated_at=CURRENT_TIMESTAMP WHERE id IN (SELECT id FROM return_requests WHERE order_id=? AND customer_id=? AND privacy_redacted=FALSE ORDER BY id LIMIT 100)",order.orderId(),customer);
            if(headers>0)return new Batch(cursor,"MORE","MORE_ROWS");
            int events=jdbc.update("UPDATE return_events SET note=NULL,actor_ref=NULL WHERE id IN (SELECT e.id FROM return_events e JOIN return_requests r ON r.id=e.return_id WHERE r.order_id=? AND r.customer_id=? AND (e.note IS NOT NULL OR e.actor_ref IS NOT NULL) ORDER BY e.id LIMIT 100)",order.orderId(),customer);
            return events>0?new Batch(cursor,"MORE","MORE_ROWS"):new Batch(order.orderId(),"MORE","RETURN_REDACTED");
        });
    }
}
