package org.demo.paymentservice.payment;

import java.math.BigDecimal;
import java.util.UUID;

/** Development adapter. The configuration forbids installing it in production. */
public final class SimulationPaymentGateway implements PaymentGateway {
    @Override public String provider() { return "simulation"; }
    @Override public java.util.Set<PaymentMethod> supportedMethods() { return java.util.Set.of(PaymentMethod.values()); }
    @Override public void requireAvailable() {}

    @Override public Authorization authorize(Long orderId, BigDecimal amount, String currency, PaymentMethod method,
                                             String idempotencyKey, boolean simulateFailure) {
        return simulateFailure
                ? new Authorization(false, null, "Payment declined by the simulation")
                : new Authorization(true, "SIM-" + UUID.nameUUIDFromBytes((orderId+":"+idempotencyKey)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().toUpperCase(java.util.Locale.ROOT), null);
    }
}
