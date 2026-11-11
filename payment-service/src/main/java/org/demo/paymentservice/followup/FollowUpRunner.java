package org.demo.paymentservice.followup;

import org.demo.paymentservice.billing.BillingClient;
import org.demo.paymentservice.delivery.DeliveryClient;
import org.demo.paymentservice.promotion.PromotionClient;
import org.demo.platformsecurity.RequestBudget;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class FollowUpRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FollowUpRunner.class);
    private final FollowUpStateStore states;
    private final BillingClient billing;
    private final DeliveryClient delivery;
    private final PromotionClient promotions;
    private final ObjectMapper json;
    private final org.demo.paymentservice.order.OrderClient orders;
    public FollowUpRunner(FollowUpStateStore states,BillingClient billing,DeliveryClient delivery,PromotionClient promotions,ObjectMapper json,
            org.demo.paymentservice.order.OrderClient orders) {
        this.states=states;this.billing=billing;this.delivery=delivery;this.promotions=promotions;this.json=json;this.orders=orders;
    }
    /** Opportunistic delivery preserves the immediate happy path; SQL remains the recovery source. */
    public void runOrder(Long order) {
        try (var budget=RequestBudget.open(8000)) {
            for(Long id:states.forOrder(order)) { if(!RequestBudget.available()) break;runOne(id); }
        } catch(RuntimeException error) { log.warn("Payment follow-up dispatch deferred for order {}",order); }
    }
    public void runOne(Long id) {
        RequestBudget.check();
        var ticket=states.claim(id);if(ticket==null) return;
        try {
            RequestBudget.check();if(!states.owns(ticket)) return;
            var current=orders.getOrder(ticket.orderId());
            if(current.lifecycleReviewRequired()) throw new OrderStateConflict();
            if(ticket.kind()==FollowUpKind.SHIPMENT_TRACKING || ticket.kind()==FollowUpKind.PROMOTION_REDEMPTION) {
                boolean paid=current.paymentState()==null ? java.util.Set.of("PAID","SHIPPED","COMPLETED").contains(current.status()==null ? "" : current.status()) : "PAID".equals(current.paymentState());
                boolean deferred="COLLECTION_DUE".equals(current.paymentState()) && current.deferredPaymentMethod()!=null && !"CANCELLED".equals(current.status());
                if(!paid && !deferred)
                    throw new OrderStateConflict();
            }
            Long result=null;
            switch(ticket.kind()) {
                case PROMOTION_REDEMPTION -> { var data=read(ticket,FollowUpPlanner.Coupon.class);requireOrder(ticket,data.orderId());promotions.redeemCoupon(data.code(),data.orderId()); }
                case SHIPMENT_TRACKING -> { var data=read(ticket,FollowUpPlanner.Tracking.class);requireOrder(ticket,data.orderId());delivery.ensureTracking(data.orderId(),data.method()); }
                case INVOICE_ISSUE -> { var data=read(ticket,FollowUpPlanner.Invoice.class);requireOrder(ticket,data.order().id());result=billing.issue(data.billingData(),data.order()); }
                case PAYMENT_NOTIFICATION, PAYMENT_PENDING_NOTIFICATION -> { var data=read(ticket,FollowUpPlanner.Notice.class);requireOrder(ticket,data.orderId()); }
                case ORDER_SETTLEMENT -> {
                    var data=read(ticket,FollowUpPlanner.Settlement.class);requireOrder(ticket,data.orderId());
                    var cash=states.collection(ticket.orderId());
                    var resultOrder=orders.collectDeferred(ticket.orderId(),cash.paymentId(),cash.reference(),cash.amount(),cash.currency());
                    if(!"PAID".equals(resultOrder.paymentState()) || !cash.paymentId().equals(resultOrder.deferredPaymentId()))
                        throw new IllegalStateException("Order did not confirm cash settlement");
                }
            }
            states.complete(ticket,result);
        } catch(RuntimeException error) {
            String code="DEPENDENCY_UNAVAILABLE";boolean permanent=false;
            if(error instanceof InvalidPayload) { code="INVALID_PAYLOAD";permanent=true; }
            if(error instanceof OrderStateConflict) { code="ORDER_STATE_CONFLICT";permanent=true; }
            for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
                if(cause instanceof org.springframework.web.client.RestClientResponseException http) {
                    int status=http.getStatusCode().value();code="HTTP_"+status;
                    permanent=status>=400 && status<500 && !java.util.Set.of(408,409,425,429).contains(status);break;
                }
            }
            states.fail(ticket,code,permanent);
            log.warn("Payment follow-up {} ({}) deferred: {}",ticket.id(),ticket.kind(),code);
        }
    }
    private <T> T read(FollowUpStateStore.Ticket ticket,Class<T> type) {
        try { var result=json.readValue(ticket.payload(),type);if(result==null) throw new InvalidPayload();return result; }
        catch(RuntimeException error) { throw new InvalidPayload(); }
    }
    private void requireOrder(FollowUpStateStore.Ticket ticket,Long order) {
        if(!ticket.orderId().equals(order)) throw new InvalidPayload();
    }
    private static final class InvalidPayload extends RuntimeException {}
    private static final class OrderStateConflict extends RuntimeException {}
}
