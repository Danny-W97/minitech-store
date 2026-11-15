package org.demo.orderservice.order;

import java.time.Duration;
import java.time.Instant;

@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="order.saga.recovery-enabled",havingValue="true",matchIfMissing=true)
public class OrderSagaRecovery {
    private static final Duration NEW_SAGA_GRACE = Duration.ofSeconds(2);
    private final OrderSagaService sagas;
    public OrderSagaRecovery(OrderSagaService sagas) { this.sagas=sagas; }
    // Allow an active POST /orders call to claim its newly committed saga before background recovery steps in.
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${order.saga.recovery-fixed-delay-ms:5000}")
    public void recover() { sagas.resumeDueSagas(Instant.now().minus(NEW_SAGA_GRACE)); }
}
