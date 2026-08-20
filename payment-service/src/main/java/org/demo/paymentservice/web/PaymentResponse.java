package org.demo.paymentservice.web;

import org.demo.paymentservice.payment.Payment;
import org.demo.paymentservice.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        Long orderId,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        PaymentStatus status,
        String providerReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getProviderReference(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
