package org.demo.orderservice.order;

@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="order.saga.recovery-enabled",havingValue="true",matchIfMissing=true)
public class OrderSagaRecovery {
    private final OrderSagaService sagas;
    public OrderSagaRecovery(OrderSagaService sagas) { this.sagas=sagas; }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${order.saga.recovery-fixed-delay-ms:5000}")
    public void recover() { sagas.resumeDueSagas(); }
}
