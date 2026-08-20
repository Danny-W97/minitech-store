package org.demo.paymentservice.web;

import jakarta.validation.Valid;
import org.demo.paymentservice.payment.PaymentRequestCoordinator;
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

    private final PaymentService paymentService;
    private final PaymentRequestCoordinator paymentRequestCoordinator;

    public PaymentController(
            PaymentService paymentService,
            PaymentRequestCoordinator paymentRequestCoordinator
    ) {
        this.paymentService = paymentService;
        this.paymentRequestCoordinator = paymentRequestCoordinator;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        PaymentResponse response = paymentRequestCoordinator.execute(
                request.orderId(),
                () -> paymentService.create(request, idempotencyKey)
        );
        HttpStatus status = response.status() == PaymentStatus.FAILED
                ? HttpStatus.PAYMENT_REQUIRED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    public List<PaymentResponse> getAll() {
        return paymentService.getAll();
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable Long id) {
        return paymentService.get(id);
    }

    @GetMapping("/order/{orderId}")
    public PaymentResponse getByOrderId(@PathVariable Long orderId) {
        return paymentService.getByOrderId(orderId);
    }
}
