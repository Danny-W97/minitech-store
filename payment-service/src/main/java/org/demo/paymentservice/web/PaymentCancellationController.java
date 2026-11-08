package org.demo.paymentservice.web;

import org.demo.paymentservice.payment.PaymentStateStore;
import org.demo.platformsecurity.Access;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;

@RestController
public class PaymentCancellationController {
    public record Fence(Long orderId,boolean cancelled) {}
    private final PaymentStateStore states;
    public PaymentCancellationController(PaymentStateStore states) { this.states=states; }
    @PostMapping("/internal/payments/orders/{id}/cancel")
    public Fence cancel(@PathVariable Long id) {
        Access.require(Access.actor().internal() && "order-service".equals(Access.actor().service()));
        if(id<=0) throw new IllegalArgumentException("Positive order ID required");
        try { states.fenceCancellation(id); }
        catch(DataIntegrityViolationException race) { states.fenceCancellation(id); }
        return new Fence(id,true);
    }
}
