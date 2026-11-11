package org.demo.promotionservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class PromotionCommerceCleanup implements CommerceCleanupAdapter,FinancePreparationAdapter {
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;
    public PromotionCommerceCleanup(PrivacyFenceStore fences,CommerceOrderSource source){this.fences=fences;this.source=source;}
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
            CommerceLocks.lock(jdbc,order.orderId());var rows=jdbc.queryForList("SELECT state,code FROM coupon_reservations WHERE order_id=?",order.orderId());
            if(order.couponExpected()&&rows.isEmpty()||!order.couponExpected()&&!rows.isEmpty())return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!rows.isEmpty()&&"RESERVED".equals(rows.getFirst().get("state")))return Batch.waitFor(cursor,"WAITING_COUPON");
            if(!rows.isEmpty()&&!java.util.Set.of("CONSUMED","RELEASED").contains(rows.getFirst().get("state")))return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!rows.isEmpty()&&!rows.getFirst().get("state").equals(order.status().equals("COMPLETED")?"CONSUMED":"RELEASED"))return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            var redemptions=jdbc.queryForList("SELECT code FROM promotion_redemptions WHERE order_id=?",String.class,order.orderId());
            if(rows.isEmpty()&&!redemptions.isEmpty()||!rows.isEmpty()&&("CONSUMED".equals(rows.getFirst().get("state"))?(redemptions.size()!=1||!redemptions.getFirst().equals(rows.getFirst().get("code"))):!redemptions.isEmpty()))return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(prepare&&order.orderVersion()!=expectedVersion)return Batch.waitFor(cursor,"ORDER_REVIEW_REQUIRED");
            if(!prepare&&!order.financeCleared())return Batch.waitFor(cursor,"WAITING_A43_36");
            CommerceLocks.retain(jdbc,order.orderId(),customer,request);return new Batch(order.orderId(),"MORE","IDENTIFIERS_RETAINED");
        });
    }
}
