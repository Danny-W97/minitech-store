package org.demo.orderservice.order;

public enum OrderSagaState {
    INVENTORY_RESERVATION_PENDING,
    WAITING_FOR_PAYMENT,
    COMPENSATION_PENDING,
    COMPLETED,
    FAILED
}
