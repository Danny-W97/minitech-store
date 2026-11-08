package org.demo.paymentservice.payment;

import org.demo.paymentservice.order.OrderClient;
import org.demo.paymentservice.inventory.InventoryClient;
import org.demo.paymentservice.followup.FollowUpRunner;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** Canonical provider state + local SQL journal drive recovery, independently of webhook retries. */
@Service
public class StripeCheckoutService {
    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final PaymentStateStore states;
    private final OrderClient orders;
    private final InventoryClient inventory;
    private final FollowUpRunner followUps;
    private final PaymentRefundStateStore refundStates;
    private final PaymentRefundService refunds;
    private final StripeExternalRefundService externalRefunds;
    public StripeCheckoutService(PaymentGateway gateway,PaymentRepository payments,PaymentStateStore states,OrderClient orders,
            InventoryClient inventory,FollowUpRunner followUps,PaymentRefundStateStore refundStates,PaymentRefundService refunds,StripeExternalRefundService externalRefunds) {
        this.gateway=gateway;this.payments=payments;this.states=states;this.orders=orders;this.inventory=inventory;
        this.followUps=followUps;this.refundStates=refundStates;this.refunds=refunds;
        this.externalRefunds=externalRefunds;
    }
    public void event(String eventId,String type,boolean liveMode,String sessionId,Long orderId,long amount,String currency) {
        if(!(gateway instanceof StripePaymentGateway stripe) || stripe.liveMode()!=liveMode) throw bad("Stripe event mode mismatch");
        var payment=payments.findByCheckoutSessionId(sessionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe session is not yet recorded"));
        if(!payment.getOrderId().equals(orderId) || payment.getAmount().movePointRight(2).longValueExact()!=amount || !payment.getCurrency().equalsIgnoreCase(currency)) throw bad("Stripe session reference or amount mismatch");
        reconcile(payment.getId(),eventId,type);
    }
    public void reconcile(Long id) { reconcile(id,null,null); }
    private void reconcile(Long id,String eventId,String type) {
        if(!(gateway instanceof StripePaymentGateway stripe)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe is not configured");
        var payment=payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
        if(payment.getCheckoutSessionId()==null) throw bad("Payment has no Stripe session");
        if(payment.getStripeExternalRefundId()!=null) { externalRefunds.process(id);receipt(eventId,type,id);return; }
        StripePaymentGateway.Session proof;
        try {
            proof=stripe.session(payment.getCheckoutSessionId());
            if(!payment.getOrderId().equals(proof.orderId()) || payment.getAmount().movePointRight(2).longValueExact()!=proof.amount()
                    || !payment.getCurrency().equalsIgnoreCase(proof.currency())) throw new StripeReviewRequiredException("SESSION_AMOUNT_CONFLICT");
        } catch(StripeReviewRequiredException review) { states.stripePreflightReview(id,review.getMessage());throw review; }
        if(refundStates.byPayment(id).filter(row -> !row.rejectedWithoutMoney()).isPresent() || payment.getStatus()==PaymentStatus.REFUNDED) { receipt(eventId,type,id);return; }
        if(!"paid".equals(proof.paymentStatus()) && "expired".equals(proof.status()) && payment.getStatus()==PaymentStatus.FAILED && payment.isProviderDeclined()) {
            cancelExpired(payment);receipt(eventId,type,id);return;
        }
        var ticket=states.claimStripeRecovery(id,"paid".equals(proof.paymentStatus()));
        if(ticket.token()==null) {
            if(payment.getStatus()==PaymentStatus.SUCCEEDED && (!"paid".equals(proof.paymentStatus()) || !proof.intent().equals(payment.getProviderReference())))
                throw new StripeReviewRequiredException("RECORDED_PAYMENT_PROVIDER_CONFLICT");
            receipt(eventId,type,id);return;
        }
        try(var budget=org.demo.platformsecurity.RequestBudget.open(45000)) {
            if("paid".equals(proof.paymentStatus())) {
                states.recordStripePaid(ticket,proof.intent(),eventId,type);
                var order=orders.getOrder(payment.getOrderId());
                if(order.totalPrice().compareTo(payment.getAmount())!=0) throw new StripeReviewRequiredException("ORDER_AMOUNT_CONFLICT");
                if(ticket.payment().stripeCompensationRequired() || "CANCELLED".equals(order.status())) {
                    refunds.automatic(ticket,proof,"LATE_PAID_CHECKOUT");return;
                }
                if(order.lifecycleReviewRequired()) throw new StripeReviewRequiredException("ORDER_REVIEW_REQUIRED");
                if(!Set.of("CONFIRMED","PAID","SHIPPED","COMPLETED").contains(order.status())) throw new StripeReviewRequiredException("ORDER_STATE_CONFLICT");
                states.assertOwner(ticket);
                try { inventory.confirmDeferred(order.id(),order.inventoryReservationId()); }
                catch(RuntimeException failure) {
                    if("CONFIRMED".equals(order.status()) && Set.of(404,409).contains(httpStatus(failure))) {
                        refunds.automatic(ticket,proof,"INVENTORY_UNFULFILLABLE");return;
                    }
                    throw failure;
                }
                states.assertOwner(ticket);
                if("CONFIRMED".equals(order.status())) {
                    order=orders.markPaid(order.id());
                    if(!"PAID".equals(order.status())) throw new StripeReviewRequiredException("ORDER_PAID_NOT_CONFIRMED");
                }
                states.assertOwner(ticket);ticket.payment().markSucceeded(proof.intent());
                // Notification waits for the invoice dependency in the durable follow-up graph.
                states.finishStripeCheckout(ticket,null,eventId,type);followUps.runOrder(payment.getOrderId());
            } else if("expired".equals(proof.status()) && "unpaid".equals(proof.paymentStatus())) {
                if(ticket.payment().stripePaid()) throw new StripeReviewRequiredException("PAID_PROOF_CONFLICTS_WITH_EXPIRY");
                ticket.payment().markDeclined("Stripe Checkout expired without payment");
                states.finishExpiredStripeCheckout(ticket,null,eventId,type);
                cancelExpired(ticket.payment());
            } else { states.stripeRetry(ticket,null);receipt(eventId,type,id); }
        } catch(StripeReviewRequiredException review) { if(ticket.payment().processing()) states.stripeReview(ticket,review.getMessage());throw review; }
        catch(RuntimeException failure) { if(ticket.payment().processing()) states.stripeRetry(ticket,"DEPENDENCY_UNAVAILABLE");throw failure; }
        finally { states.release(ticket); }
    }
    private void cancelExpired(Payment payment) {
        var order=orders.cancel(payment.getOrderId());
        if(!"CANCELLED".equals(order.status())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Expired order cancellation is pending");
        states.stripeCancellationResolved(payment.getId());
    }
    private void receipt(String event,String type,Long id) { if(event!=null) states.recordCompletedStripeEvent(event,type,id); }
    private int httpStatus(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof org.springframework.web.client.RestClientResponseException http) return http.getStatusCode().value();
        return 0;
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
