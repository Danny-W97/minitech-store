package org.demo.orderservice.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.demo.orderservice.order.DeferredFulfillmentService;
import org.demo.platformsecurity.Access;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/internal/orders/{id}/deferred")
public class InternalDeferredFulfillmentController {
    public record Acceptance(@NotNull @Positive Long paymentId,@NotBlank String paymentMethod,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal amount,@NotBlank @Pattern(regexp="PLN") String currency) {}
    public record Collection(@NotNull @Positive Long paymentId,@NotBlank @Size(max=80) String receiptReference,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal amount,@NotBlank @Pattern(regexp="PLN") String currency) {}
    private final DeferredFulfillmentService fulfillment;
    public InternalDeferredFulfillmentController(DeferredFulfillmentService fulfillment) { this.fulfillment=fulfillment; }
    private void requirePayment() { Access.require(Access.actor().internal() && "payment-service".equals(Access.actor().service())); }
    @PostMapping("/accept") public OrderResponse accept(@PathVariable Long id,@Valid @RequestBody Acceptance request) {
        requirePayment();return fulfillment.accept(id,request.paymentId(),request.paymentMethod(),request.amount(),request.currency());
    }
    @PostMapping("/collect") public OrderResponse collect(@PathVariable Long id,@Valid @RequestBody Collection request) {
        requirePayment();return fulfillment.collect(id,request.paymentId(),request.receiptReference(),request.amount(),request.currency());
    }
}
