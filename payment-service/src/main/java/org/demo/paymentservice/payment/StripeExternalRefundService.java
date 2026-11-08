package org.demo.paymentservice.payment;

import org.demo.paymentservice.order.OrderClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Observes provider-side refunds; this service never creates a refund or moves money. */
@Service
public class StripeExternalRefundService {
    private final PaymentRepository payments;
    private final PaymentStateStore states;
    private final PaymentGateway gateway;
    private final OrderClient orders;
    public StripeExternalRefundService(PaymentRepository payments,PaymentStateStore states,PaymentGateway gateway,OrderClient orders) {
        this.payments=payments;this.states=states;this.gateway=gateway;this.orders=orders;
    }
    public Long observe(String refundId) {
        var stripe=stripe();var refund=stripe.refund(refundId);
        var payment=payments.findByStripeIntentId(refund.intent()).orElse(null);
        if(payment==null) {
            Long orderId=stripe.orderForIntent(refund.intent());
            if(orderId==null) return null;
            payment=payments.findByOrderId(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"External refund payment is not recorded yet"));
        }
        if(payment.getCheckoutSessionId()==null) throw new StripeReviewRequiredException("EXTERNAL_REFUND_NOT_A_STRIPE_CHECKOUT");
        var proof=stripe.session(payment.getCheckoutSessionId());
        states.observeExternalRefund(payment.getId(),proof,refund);
        process(payment.getId(),refund);return payment.getId();
    }
    public void process(Long id) {
        var payment=payments.findById(id).orElseThrow();
        if(payment.getStripeExternalRefundId()!=null) process(id,stripe().refund(payment.getStripeExternalRefundId()));
    }
    private void process(Long id,StripePaymentGateway.Refund refund) {
        var payment=payments.findById(id).orElseThrow();
        if(!refund.id().equals(payment.getStripeExternalRefundId())) return;
        if(!refund.intent().equals(payment.getStripeIntentId()) || !payment.getCurrency().equalsIgnoreCase(refund.currency())
                || refund.amount()>payment.getAmount().movePointRight(2).longValueExact()) throw new StripeReviewRequiredException("EXTERNAL_REFUND_IDENTITY_CONFLICT");
        if(java.util.Set.of("failed","canceled").contains(refund.status())) {
            states.externalRefundResult(id,refund.id(),false,"EXTERNAL_REFUND_FAILED_REVIEW",false);return;
        }
        boolean full=refund.amount()==payment.getAmount().movePointRight(2).longValueExact();
        if(!states.externalRefundProgress(id,refund.id(),full && "succeeded".equals(refund.status()))) return;
        var hold=orders.refund(payment.getOrderId(),"begin",payment.getId(),payment.getAmount(),payment.getCurrency(),false,true);
        if(!hold.lifecycleReviewRequired()) throw new StripeReviewRequiredException("EXTERNAL_REFUND_ORDER_HOLD_CONFLICT");
        if(full && "succeeded".equals(refund.status())) {
            var completed=orders.refund(payment.getOrderId(),"complete",payment.getId(),payment.getAmount(),payment.getCurrency(),false,true);
            if(!"REFUNDED".equals(completed.paymentState()) || !completed.lifecycleReviewRequired())
                throw new StripeReviewRequiredException("EXTERNAL_REFUND_ORDER_HOLD_CONFLICT");
            states.externalRefundResult(id,refund.id(),true,"EXTERNAL_REFUND_ORDER_REVIEW",false);
        } else states.externalRefundResult(id,refund.id(),false,full ? ("pending".equals(refund.status()) ? "EXTERNAL_REFUND_PENDING" : "EXTERNAL_REFUND_REQUIRES_ACTION_REVIEW") : "EXTERNAL_PARTIAL_REFUND_REVIEW","pending".equals(refund.status()));
    }
    private StripePaymentGateway stripe() {
        if(gateway instanceof StripePaymentGateway stripe) return stripe;
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe is not configured");
    }
}
