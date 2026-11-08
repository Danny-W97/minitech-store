package org.demo.deliveryservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class DeliveryCommerceCleanup implements CommerceCleanupAdapter,FinancePreparationAdapter {
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;
    public DeliveryCommerceCleanup(PrivacyFenceStore fences,CommerceOrderSource source){this.fences=fences;this.source=source;}
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
            CommerceLocks.lock(jdbc,order.orderId());var rows=jdbc.queryForList("SELECT status,sync_state,sync_token,sync_until FROM shipments WHERE order_id=?",order.orderId());
            if(order.shipmentExpected()&&rows.isEmpty())return Batch.waitFor(cursor,"SHIPMENT_REVIEW_REQUIRED");
            if(!rows.isEmpty()){
                var row=rows.getFirst();if("REVIEW_REQUIRED".equals(row.get("sync_state")))return Batch.waitFor(cursor,"SHIPMENT_REVIEW_REQUIRED");
                if(!java.util.Set.of("DELIVERED","PICKED_UP","CANCELLED").contains(row.get("status"))||!"SYNCED".equals(row.get("sync_state"))||row.get("sync_token")!=null||row.get("sync_until")!=null)return Batch.waitFor(cursor,"WAITING_SHIPMENT");
                boolean cancelledShipment="CANCELLED".equals(row.get("status"));
                if(cancelledShipment!=order.status().equals("CANCELLED"))return Batch.waitFor(cursor,"SHIPMENT_REVIEW_REQUIRED");
            }
            if(prepare&&order.orderVersion()!=expectedVersion)return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!prepare&&!order.financeCleared())return Batch.waitFor(cursor,"WAITING_A43_36");
            CommerceLocks.retain(jdbc,order.orderId(),customer,request);return new Batch(order.orderId(),"MORE","IDENTIFIERS_RETAINED");
        });
    }
}
