package org.demo.paymentservice.web;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.OrderAccess;

import jakarta.validation.Valid;
import org.demo.paymentservice.payment.PaymentService;
import org.demo.paymentservice.payment.PaymentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final OrderAccess orderAccess;

    private final PaymentService paymentService;

    public PaymentController(
            PaymentService paymentService, OrderAccess orderAccess
    ) {
        this.orderAccess = orderAccess;
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        orderAccess.require(request.orderId());
        if (request.customerId() != null) Access.customer(request.customerId());
        PaymentResponse response = paymentService.create(request, idempotencyKey);
        HttpStatus status = response.status() == PaymentStatus.FAILED
                ? HttpStatus.PAYMENT_REQUIRED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PaymentResponse>> getAll(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) PaymentStatus status) {
        return paymentService.list(status, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @GetMapping("/config")
    public PaymentConfigResponse config() { return paymentService.configuration(); }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable Long id) {
        PaymentResponse response = paymentService.get(id);
        orderAccess.require(response.orderId());
        return response;
    }

    @GetMapping("/order/{orderId}")
    public PaymentResponse getByOrderId(@PathVariable Long orderId) {
        orderAccess.require(orderId);
        return paymentService.getByOrderId(orderId);
    }
}
