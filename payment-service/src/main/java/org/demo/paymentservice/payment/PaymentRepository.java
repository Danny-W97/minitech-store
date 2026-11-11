package org.demo.paymentservice.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Payment p where p.orderId=:id")
    Optional<Payment> lockOrder(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findByCollectionReference(String collectionReference);
    Optional<Payment> findByCheckoutSessionId(String checkoutSessionId);
    Optional<Payment> findByStripeIntentId(String stripeIntentId);
    @org.springframework.data.jpa.repository.Query("select p.id from Payment p where p.checkoutSessionId is not null and (p.status in (org.demo.paymentservice.payment.PaymentStatus.PENDING,org.demo.paymentservice.payment.PaymentStatus.FAILED) or p.stripeExternalRefundId is not null) "
            +"and p.stripeNextAttemptAt<=:now and (p.processingUntil is null or p.processingUntil<=:now) order by p.stripeNextAttemptAt,p.id")
    List<Long> stripeDue(java.time.Instant now,org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Query("select p.orderId from Payment p where p.id=:id")
    Optional<Long> orderIdForPayment(Long id);

    List<Payment> findAllByOrderByCreatedAtDesc();

    @org.springframework.data.jpa.repository.Query("select p from Payment p where p.id <= :ceiling "
            + "and (:status is null or p.status = :status) order by p.id desc")
    List<Payment> page(@org.springframework.data.repository.query.Param("ceiling") long ceiling,
            @org.springframework.data.repository.query.Param("status") PaymentStatus status, org.springframework.data.domain.Pageable pageable);
}
