package org.demo.paymentservice.payment;

/** Stable diagnostic code, never the provider error body or a secret. */
public class StripeReviewRequiredException extends RuntimeException {
    public StripeReviewRequiredException(String code) { super(code); }
}
