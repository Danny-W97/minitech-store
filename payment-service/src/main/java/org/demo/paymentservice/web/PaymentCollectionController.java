package org.demo.paymentservice.web;

import jakarta.validation.Valid;
import org.demo.paymentservice.payment.PaymentCollectionService;
import org.demo.platformsecurity.Access;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentCollectionController {
    private final PaymentCollectionService collection;
    public PaymentCollectionController(PaymentCollectionService collection) { this.collection=collection; }
    @PostMapping("/payments/{id}/collect")
    public PaymentResponse collect(@PathVariable Long id,@Valid @RequestBody CollectPaymentRequest request) {
        Access.require(Access.actor().operator());return collection.collect(id,request);
    }
}
