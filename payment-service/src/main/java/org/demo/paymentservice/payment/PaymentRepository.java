package org.demo.paymentservice.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findAllByOrderByCreatedAtDesc();
}
