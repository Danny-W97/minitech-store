package org.demo.paymentservice.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments/webhooks")
public class StripeWebhookController {
    private final StripeWebhookService webhooks;
    public StripeWebhookController(StripeWebhookService webhooks) { this.webhooks=webhooks; }

    @PostMapping(value="/stripe",consumes="application/json")
    public ResponseEntity<Void> stripe(@RequestBody byte[] body,
            @RequestHeader(value="Stripe-Signature",required=false) String signature) {
        webhooks.receive(body,signature);
        return ResponseEntity.ok().build();
    }
}
