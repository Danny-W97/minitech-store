package org.demo.paymentservice.web;

import org.demo.paymentservice.payment.*;
import org.demo.platformsecurity.Access;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
public class PaymentRefundController {
    public record Request(@NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @NotBlank @Pattern(regexp="CUSTOMER_REQUEST|DUPLICATE_PAYMENT|OPERATOR_RECONCILIATION") String reason) {}
    public record Response(Long id,Long paymentId,Long orderId,BigDecimal amount,String currency,PaymentRefund.Status status,
            String providerRefundId,String businessOutcome,String lastError,Instant createdAt) {
        static Response from(PaymentRefund row) { return new Response(row.getId(),row.getPaymentId(),row.getOrderId(),row.getAmount(),
                row.getCurrency(),row.getStatus(),row.getProviderRefundId(),row.getBusinessOutcome(),row.getLastError(),row.getCreatedAt()); }
    }
    private final PaymentRefundService service;
    private final PaymentRefundStateStore states;
    public PaymentRefundController(PaymentRefundService service,PaymentRefundStateStore states) { this.service=service;this.states=states; }
    @PostMapping("/payments/{paymentId}/refunds")
    public Response request(@PathVariable Long paymentId,@Valid @RequestBody Request request) {
        var actor=Access.actor();
        Access.require(actor.operator() || (actor.internal() && "returns-service".equals(actor.service())));
        String actorRef=actor.internal() ? "service:"+actor.service() : (actor.email()==null ? "operator" : actor.email());
        return Response.from(service.request(paymentId,request.idempotencyKey(),request.amount(),request.reason(),actorRef));
    }
    @GetMapping("/payments/{paymentId}/refunds")
    public List<Response> list(@PathVariable Long paymentId) { operator();return states.byPayment(paymentId).stream().map(Response::from).toList(); }
    @GetMapping("/payments/refunds/{id}")
    public Response get(@PathVariable Long id) { operator();return Response.from(states.get(id)); }
    @PostMapping("/payments/refunds/{id}/reconcile")
    public Response reconcile(@PathVariable Long id) { operator();service.reconcile(id);return Response.from(states.get(id)); }
    private void operator() { Access.require(Access.actor().operator()); }
}
