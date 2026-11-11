package org.demo.orderservice.order;

@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="order.saga.recovery-enabled",havingValue="true",matchIfMissing=true)
public class OrderReconciliationRecovery {
    private final OrderReconciliationService reconciliation;
    public OrderReconciliationRecovery(OrderReconciliationService reconciliation) { this.reconciliation=reconciliation; }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${order.saga.reconciliation-fixed-delay-ms:30000}")
    public void scan() { reconciliation.scanMissing(); }
}
