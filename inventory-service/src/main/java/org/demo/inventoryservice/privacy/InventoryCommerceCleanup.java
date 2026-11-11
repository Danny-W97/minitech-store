package org.demo.inventoryservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class InventoryCommerceCleanup implements CommerceCleanupAdapter,FinancePreparationAdapter {
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;
    public InventoryCommerceCleanup(PrivacyFenceStore fences,CommerceOrderSource source){this.fences=fences;this.source=source;}
    public Batch cleanup(long customer,UUID request,long cursor){return process(customer,request,cursor,false,-1);}
    public FinancePreparationAdapter.Result prepare(long customer,UUID request,long order,long version,String cursor){
        fences.underFence(customer,request,jdbc->true);
        var row=FinancePreparationAdapter.exact(source,customer,request,order,version);
        if(!row.eligibility().equals("ELIGIBLE"))return FinancePreparationAdapter.Result.waitFor(row.eligibility(),"0-0");
        var result=process(customer,request,order-1,true,version);
        return result.outcome().equals("WAIT")?FinancePreparationAdapter.Result.waitFor(result.code(),"0-0"):FinancePreparationAdapter.Result.prepared();
    }
    private Batch process(long customer,UUID request,long cursor,boolean prepare,long expectedVersion){
        fences.underFence(customer,request,jdbc->true);var next=source.next(customer,request,cursor);if(next.isEmpty())return Batch.end(cursor);var order=next.get();
        if(!order.eligibility().equals("ELIGIBLE"))return Batch.waitFor(cursor,order.eligibility());
        return fences.underFence(customer,request,jdbc->{
            CommerceLocks.lock(jdbc,order.orderId());var rows=jdbc.queryForList("SELECT id,status FROM inventory_reservations WHERE order_id=?",order.orderId());
            if(rows.size()>1||order.reservationId()>0&&(rows.isEmpty()||((Number)rows.getFirst().get("id")).longValue()!=order.reservationId()))return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!rows.isEmpty()&&"RESERVED".equals(rows.getFirst().get("status")))return Batch.waitFor(cursor,"WAITING_RESERVATION");
            if(!rows.isEmpty()&&!java.util.Set.of("CONFIRMED","RELEASED").contains(rows.getFirst().get("status")))return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!rows.isEmpty()&&!rows.getFirst().get("status").equals(order.status().equals("COMPLETED")?"CONFIRMED":"RELEASED"))return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(prepare&&order.orderVersion()!=expectedVersion)return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!prepare&&!order.financeCleared())return Batch.waitFor(cursor,"WAITING_A43_36");
            CommerceLocks.retain(jdbc,order.orderId(),customer,request);return new Batch(order.orderId(),"MORE","IDENTIFIERS_RETAINED");
        });
    }
}
