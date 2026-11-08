package org.demo.paymentservice.followup;

import org.demo.paymentservice.billing.BillingDataRequest;
import org.demo.paymentservice.order.OrderResponse;
import org.demo.paymentservice.outbox.PaymentOutboxEvent;
import org.demo.paymentservice.web.CreatePaymentRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

@Component
public class FollowUpPlanner {
    public record Tracking(Long orderId,String method) {}
    public record Coupon(Long orderId,String code) {}
    public record Settlement(Long orderId) {}
    public record Invoice(OrderResponse order,BillingDataRequest billingData) {}
    public record Notice(Long orderId,BigDecimal amount,String recipient,String address,String correlationId,boolean invoiceRequested) {}
    private final ObjectMapper json;
    public FollowUpPlanner(ObjectMapper json) { this.json=json; }
    public List<PaymentFollowUp> plan(CreatePaymentRequest request,OrderResponse order,PaymentOutboxEvent notice) {
        return plan(request,order,notice,false,false);
    }
    public List<PaymentFollowUp> deferred(CreatePaymentRequest request,OrderResponse order,PaymentOutboxEvent notice) {
        return plan(request,order,notice,true,false);
    }
    public List<PaymentFollowUp> awaitingStripeCheckout(CreatePaymentRequest request,OrderResponse order,PaymentOutboxEvent notice) {
        return plan(request,order,notice,false,true);
    }
    private List<PaymentFollowUp> plan(CreatePaymentRequest request,OrderResponse order,PaymentOutboxEvent notice,
            boolean deferred,boolean awaitingPayment) {
        var tasks=new ArrayList<PaymentFollowUp>();
        if(order.promotionCode()!=null && !order.promotionCode().isBlank())
            tasks.add(task(order.id(),FollowUpKind.PROMOTION_REDEMPTION,new Coupon(order.id(),order.promotionCode()),awaitingPayment));
        tasks.add(task(order.id(),FollowUpKind.SHIPMENT_TRACKING,new Tracking(order.id(),order.deliveryMethod()),awaitingPayment));
        boolean invoice=Boolean.TRUE.equals(request.invoiceRequested());
        if(invoice) {
            if(request.billingData()==null) throw new IllegalArgumentException("Invoice data is required");
            tasks.add(task(order.id(),FollowUpKind.INVOICE_ISSUE,new Invoice(order,request.billingData()),deferred || awaitingPayment));
        }
        if(notice!=null) tasks.add(task(order.id(),FollowUpKind.PAYMENT_NOTIFICATION,
                new Notice(order.id(),notice.getAmount(),notice.getRecipient(),notice.getDeliveryAddress(),notice.getCorrelationId(),invoice),invoice || deferred || awaitingPayment));
        if(deferred) {
            tasks.add(task(order.id(),FollowUpKind.ORDER_SETTLEMENT,new Settlement(order.id()),true));
            if(notice!=null) tasks.add(task(order.id(),FollowUpKind.PAYMENT_PENDING_NOTIFICATION,
                    new Notice(order.id(),notice.getAmount(),notice.getRecipient(),notice.getDeliveryAddress(),notice.getCorrelationId(),false),false));
        }
        return tasks;
    }
    private PaymentFollowUp task(Long order,FollowUpKind kind,Object payload,boolean waiting) {
        String encoded=json.writeValueAsString(payload);
        if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_048_576)
            throw new IllegalArgumentException("Payment follow-up payload exceeds its limit");
        return new PaymentFollowUp(order,kind,encoded,waiting);
    }
}
