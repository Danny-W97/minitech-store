package org.demo.orderservice.payment;

public class CancellationRefusedException extends org.springframework.web.server.ResponseStatusException {
    public CancellationRefusedException() {
        super(org.springframework.http.HttpStatus.CONFLICT,"Payment or fulfillment requires an explicit refund/compensation process");
    }
}
