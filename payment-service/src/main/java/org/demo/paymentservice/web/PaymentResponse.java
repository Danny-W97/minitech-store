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
        Instant updatedAt,
        String collectionReference,
        Instant collectedAt,
        String checkoutUrl,
        String checkoutSessionId
) {
    public PaymentResponse(Long id,Long orderId,BigDecimal amount,String currency,String paymentMethod,PaymentStatus status,
            String providerReference,String failureReason,Instant createdAt,Instant updatedAt) {
        this(id,orderId,amount,currency,paymentMethod,status,providerReference,failureReason,createdAt,updatedAt,null,null,null,null);
    }

    public PaymentResponse(Long id,Long orderId,BigDecimal amount,String currency,String paymentMethod,PaymentStatus status,
            String providerReference,String failureReason,Instant createdAt,Instant updatedAt,String collectionReference,Instant collectedAt) {
        this(id,orderId,amount,currency,paymentMethod,status,providerReference,failureReason,createdAt,updatedAt,collectionReference,collectedAt,null,null);
    }

    public PaymentResponse(Long id,Long orderId,BigDecimal amount,String currency,String paymentMethod,PaymentStatus status,
            String providerReference,String failureReason,Instant createdAt,Instant updatedAt,String collectionReference,Instant collectedAt,String checkoutUrl) {
        this(id,orderId,amount,currency,paymentMethod,status,providerReference,failureReason,createdAt,updatedAt,collectionReference,collectedAt,checkoutUrl,null);
    }

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
                payment.getUpdatedAt(),payment.getCollectionReference(),payment.getCollectedAt(),payment.getCheckoutUrl(),payment.getCheckoutSessionId()
        );
    }
}
