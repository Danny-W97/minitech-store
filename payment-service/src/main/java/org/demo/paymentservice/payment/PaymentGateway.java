package org.demo.paymentservice.payment;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Only an adapter's verified authorization may precede marking an order paid.
 * An adapter MUST reuse the original provider outcome for an idempotency key,
 * including after a timeout or replica crash. Local SQL cannot fence a remote charge.
 */
public interface PaymentGateway {
    void requireAvailable();
    default void validatePayment(BigDecimal amount,String currency,PaymentMethod method) {}
    Authorization authorize(Long orderId, BigDecimal amount, String currency, PaymentMethod method,
                            String idempotencyKey, boolean simulateFailure);

    default String provider() { return "disabled"; }
    default Set<PaymentMethod> supportedMethods() { return Set.of(PaymentMethod.CASH_ON_DELIVERY,PaymentMethod.CASH_AT_PICKUP); }

    record Authorization(boolean approved, String reference, String failureReason, String checkoutUrl) {
        public Authorization(boolean approved,String reference,String failureReason) {
            this(approved,reference,failureReason,null);
        }
    }
}
