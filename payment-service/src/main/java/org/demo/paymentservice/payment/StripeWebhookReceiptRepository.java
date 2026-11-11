package org.demo.paymentservice.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeWebhookReceiptRepository extends JpaRepository<StripeWebhookReceipt,String> {}
