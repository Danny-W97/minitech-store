package org.demo.paymentservice.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Retryable contention, distinct from a conflicting payment command. */
public class PaymentInProgressException extends ResponseStatusException {
    public PaymentInProgressException() {
        super(HttpStatus.CONFLICT, "Payment is already being processed; retry the same request");
    }
}
