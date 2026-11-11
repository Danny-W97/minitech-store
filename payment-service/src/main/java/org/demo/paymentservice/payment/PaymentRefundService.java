package org.demo.paymentservice.payment;

import org.demo.paymentservice.order.OrderClient;
import org.demo.paymentservice.inventory.InventoryClient;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;

@Service
public class PaymentRefundService {
    private final PaymentRefundStateStore states;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OrderClient orders;
    private final InventoryClient inventory;
    public PaymentRefundService(PaymentRefundStateStore states,PaymentRepository payments,PaymentGateway gateway,OrderClient orders,InventoryClient inventory) {
        this.states=states;this.payments=payments;this.gateway=gateway;this.orders=orders;this.inventory=inventory;
    }
    public PaymentRefund request(Long paymentId,String key,BigDecimal amount,String reason,String actor) {
        var payment=payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if(payment.getCheckoutSessionId()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a Stripe card payment can be refunded here");
        var proof=stripe().session(payment.getCheckoutSessionId());
        var refund=states.request(paymentId,key,amount,false,reason,actor,proof,null);
        process(refund.getId());return states.get(refund.getId());
    }
    public void automatic(PaymentStateStore.Ticket ticket,StripePaymentGateway.Session proof,String reason) {
        var refund=states.request(ticket.payment().getId(),"checkout-compensation-"+ticket.payment().getId(),ticket.payment().getAmount(),
                true,reason,"payment-service",proof,ticket.token());
        process(refund.getId());
    }
    public void process(Long id) {
        var ticket=states.claim(id);if(ticket==null) return;
        boolean holdConfirmed=false;
        try(var budget=org.demo.platformsecurity.RequestBudget.open(45000)) {
            var refund=states.get(id);
            boolean returnSettlement="service:returns-service".equals(refund.getRequestedBy());
            // A durable hold on Order precedes any request that can move money.
            var order=returnSettlement
                    ? orders.refund(refund.getOrderId(),"begin",refund.getPaymentId(),refund.getAmount(),refund.getCurrency(),false,true)
                    : orders.refund(refund.getOrderId(),"begin",refund.getPaymentId(),refund.getAmount(),refund.getCurrency(),false);
            holdConfirmed=true;states.freezeFulfillment(ticket);
            if(refund.getStatus()!=PaymentRefund.Status.SUCCEEDED) {
                StripePaymentGateway.Refund provider;
                if(refund.getProviderRefundId()!=null) provider=stripe().refund(refund.getProviderRefundId());
                else {
                    provider=stripe().findRefund(refund.getIntentId(),refund.getOperationId());
                    if(provider==null) {
                        if(refund.getSubmittedAt()!=null && refund.getSubmittedAt().isBefore(Instant.now().minusSeconds(20*3600)))
                            throw new StripeReviewRequiredException("AMBIGUOUS_REFUND_RETRY_WINDOW_EXPIRED");
                        states.submitted(ticket);
                        provider=stripe().createRefund(refund.getIntentId(),refund.getAmount().movePointRight(2).longValueExact(),refund.getOperationId(),refund.getPaymentId());
                    }
                }
                states.provider(ticket,provider);refund=states.get(id);
            }
            if(refund.getStatus()==PaymentRefund.Status.SUCCEEDED) {
                if(refund.isAutomatic()) {
                    inventory.compensateStripe(refund.getOrderId(),order.inventoryReservationId());
                    var cancelled=orders.refund(refund.getOrderId(),"complete",refund.getPaymentId(),refund.getAmount(),refund.getCurrency(),true);
                    if(!"CANCELLED".equals(cancelled.status()) || !"REFUNDED".equals(cancelled.paymentState()))
                        throw new StripeReviewRequiredException("REFUNDED_ORDER_COMPENSATION_CONFLICT");
                    states.complete(ticket,"CANCELLED_AFTER_REFUND");
                } else {
                    var reviewed=returnSettlement
                            ? orders.refund(refund.getOrderId(),"complete",refund.getPaymentId(),refund.getAmount(),refund.getCurrency(),false,true)
                            : orders.refund(refund.getOrderId(),"complete",refund.getPaymentId(),refund.getAmount(),refund.getCurrency(),false);
                    if(!"REFUNDED".equals(reviewed.paymentState()) || !reviewed.lifecycleReviewRequired())
                        throw new StripeReviewRequiredException("REFUNDED_ORDER_HOLD_CONFLICT");
                    states.complete(ticket,"REFUNDED_ORDER_REVIEW_REQUIRED");
                }
            }
        } catch(StripeReviewRequiredException review) { states.review(ticket,review.getMessage()); }
        catch(RuntimeException failure) {
            int status=0;
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof org.springframework.web.client.RestClientResponseException http) { status=http.getStatusCode().value();break; }
            }
            if(!holdConfirmed && status>=400 && status<500 && status!=429 && ticket.refund().getSubmittedAt()==null && ticket.refund().getProviderRefundId()==null)
                states.rejectEligibility(ticket,"ORDER_REFUND_HTTP_"+status);
            else if(status>=400 && status<500 && status!=429) states.review(ticket,"ORDER_OR_STOCK_HTTP_"+status);
            else states.retry(ticket,"DEPENDENCY_UNAVAILABLE");
        } finally { states.release(ticket); }
    }
    public void reconcile(Long id) { states.reconcile(id);process(id); }
    private StripePaymentGateway stripe() {
        if(gateway instanceof StripePaymentGateway stripe) return stripe;
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe is not configured");
    }
}
