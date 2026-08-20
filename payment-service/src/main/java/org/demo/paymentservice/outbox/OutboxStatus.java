package org.demo.paymentservice.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
