package org.demo.paymentservice.web;

import org.demo.paymentservice.payment.*;
import org.demo.platformsecurity.Access;
import org.springframework.web.bind.annotation.*;

@RestController
public class StripeReconciliationController {
    public record Response(PaymentResponse payment,String diagnosticCode,Long refundId,String externalRefundId) {}
    private final StripeCheckoutService stripe;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    public StripeReconciliationController(StripeCheckoutService stripe,PaymentRepository payments,PaymentRefundRepository refunds) { this.stripe=stripe;this.payments=payments;this.refunds=refunds; }
    @PostMapping("/payments/{id}/reconcile")
    public Response reconcile(@PathVariable Long id) {
        Access.require(Access.actor().operator());stripe.reconcile(id);return response(id);
    }
    @GetMapping("/payments/{id}/stripe-status")
    public Response status(@PathVariable Long id) { Access.require(Access.actor().operator());return response(id); }
    private Response response(Long id) {
        var payment=payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
        return new Response(PaymentResponse.from(payment),payment.getStripeError(),refunds.findByPaymentId(id).map(PaymentRefund::getId).orElse(null),payment.getStripeExternalRefundId());
    }
}
