package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class OrderCommerceCleanup implements CommerceCleanupAdapter {
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;
    public OrderCommerceCleanup(PrivacyFenceStore fences,CommerceOrderSource source){this.fences=fences;this.source=source;}
    public Batch cleanup(long customer,UUID request,long cursor){
        var next=source.next(customer,request,cursor);if(next.isEmpty())return Batch.end(cursor);var order=next.get();
        if(!order.eligibility().equals("ELIGIBLE"))return Batch.waitFor(cursor,order.eligibility());
        if(!order.financeCleared())return Batch.waitFor(cursor,"WAITING_A43_36");
        return fences.underFence(customer,request,jdbc->{
            CommerceLocks.lock(jdbc,order.orderId());if(!CommerceLocks.retained(jdbc,order.orderId(),customer,request))throw new IllegalStateException("Missing finance-bound order lock");
            jdbc.update("UPDATE orders SET customer_email=NULL,delivery_first_name=NULL,delivery_last_name=NULL,delivery_street=NULL,delivery_postal_code=NULL,delivery_city=NULL,delivery_country=NULL,delivery_phone=NULL,privacy_redacted=TRUE,updated_at=CURRENT_TIMESTAMP WHERE id=? AND customer_id=? AND privacy_redacted=FALSE",order.orderId(),customer);
            return new Batch(order.orderId(),"MORE","ORDER_REDACTED");
        });
    }
}
