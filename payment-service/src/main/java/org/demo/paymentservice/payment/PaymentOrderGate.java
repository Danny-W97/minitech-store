package org.demo.paymentservice.payment;

import jakarta.persistence.*;

/** Durable fence, including orders which have no Payment row yet. */
@Entity
@Table(name="payment_order_gates")
public class PaymentOrderGate {
    @Id private Long orderId;
    @Column(nullable=false) private boolean cancelled;
    protected PaymentOrderGate() {}
    public PaymentOrderGate(Long orderId) { this.orderId=orderId; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() { cancelled=true; }
}
