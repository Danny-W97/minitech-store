package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class OrderPrivacySource implements CommerceOrderSource {
    private final PrivacyFenceStore fences;private final CommerceFinanceGate finance;private final boolean enabled;
    public OrderPrivacySource(PrivacyFenceStore fences,CommerceFinanceGate finance,@Value("${privacy.commerce-cleanup.enabled:false}") boolean enabled){this.fences=fences;this.finance=finance;this.enabled=enabled;}
    public Optional<Entry> next(long customer,UUID request,long after){
        if(after<0)throw new IllegalArgumentException("Invalid commerce cursor");
        return fences.underFence(customer,request,jdbc->{
            var ids=jdbc.queryForList("SELECT id FROM orders WHERE customer_id=? AND id>? ORDER BY id LIMIT 1",Long.class,customer,after);
            if(ids.isEmpty())return Optional.empty();long id=ids.getFirst();CommerceLocks.lock(jdbc,id);
            var row=describe(jdbc,customer,id,false);boolean cleared=false;
            if(enabled&&row.eligibility().equals("ELIGIBLE")){
                FinancePrivacyJobs.enqueue(jdbc,customer,request,id,row.orderVersion());
                cleared=CommerceLocks.retained(jdbc,id,customer,request)||finance.cleared(jdbc,id,row.orderVersion());
                if(cleared)CommerceLocks.retain(jdbc,id,customer,request);
            }
            return Optional.of(new Entry(id,row.eligibility(),cleared,row.status(),row.reservationId(),row.shipmentExpected(),row.couponExpected(),row.orderVersion(),row.amount(),row.paymentState(),row.invoiceRequested()));
        });
    }
    static Entry describe(JdbcTemplate jdbc,long customer,long id,boolean cleared){
        var o=jdbc.queryForMap("SELECT * FROM orders WHERE id=? AND customer_id=?",id,customer);
        var sagas=jdbc.queryForList("SELECT state,processing_until FROM order_sagas WHERE order_id=?",id);
        String state=(String)o.get("status"),eligibility="ELIGIBLE";long reservation=o.get("inventory_reservation_id") instanceof Number n?n.longValue():0;
        boolean shipment=o.get("delivery_tracking_number")!=null||state.equals("COMPLETED"),coupon=o.get("promotion_code")!=null;
        if(Boolean.TRUE.equals(o.get("lifecycle_review_required"))||o.get("checkout_request_hash")==null||sagas.size()!=1)eligibility="ORDER_REVIEW_REQUIRED";
        else if(!Set.of("COMPLETED","CANCELLED").contains(state))eligibility="WAITING_ORDER_PROCESS";
        else if(!Set.of("COMPLETED","FAILED").contains(sagas.getFirst().get("state")))eligibility="WAITING_ORDER_PROCESS";
        else if(sagas.getFirst().get("processing_until")!=null)eligibility="WAITING_ORDER_PROCESS";
        else if((o.get("refund_state")!=null||o.get("refund_payment_id")!=null)&&!"REFUNDED".equals(o.get("refund_state")))eligibility="WAITING_ORDER_PROCESS";
        else if(coupon&&!Boolean.TRUE.equals(o.get("coupon_reservation_verified")))eligibility="ORDER_REVIEW_REQUIRED";
        else if(state.equals("COMPLETED")&&(!"COMPLETED".equals(sagas.getFirst().get("state"))||reservation<1||o.get("delivery_tracking_number")==null||!Set.of("DELIVERED","PICKED_UP").contains(Objects.toString(o.get("delivery_status"),""))||((Number)o.get("delivery_revision")).longValue()<0))eligibility="ORDER_REVIEW_REQUIRED";
        else if(state.equals("COMPLETED")&&o.get("deferred_payment_method")!=null&&o.get("collection_reference")==null)eligibility="WAITING_ORDER_PROCESS";
        String payment=o.get("refund_state")!=null?o.get("refund_state").toString():o.get("deferred_payment_method")!=null?(o.get("collection_reference")==null?"COLLECTION_DUE":"PAID"):Set.of("PAID","SHIPPED","COMPLETED").contains(state)?"PAID":"UNPAID";
        return new Entry(id,eligibility,cleared,state,reservation,shipment,coupon,((Number)o.get("version")).longValue(),(java.math.BigDecimal)o.get("total_price"),payment,Boolean.TRUE.equals(o.get("invoice_requested")));
    }
}
