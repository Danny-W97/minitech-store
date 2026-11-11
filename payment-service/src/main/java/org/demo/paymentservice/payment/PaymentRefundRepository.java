package org.demo.paymentservice.payment;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund,Long> {
    Optional<PaymentRefund> findByPaymentId(Long paymentId);
    Optional<PaymentRefund> findByOperationId(String operationId);
    Optional<PaymentRefund> findByRequestKey(String requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PaymentRefund r where r.id=:id") Optional<PaymentRefund> lockId(Long id);
    @Query("select r.id from PaymentRefund r where r.nextAttemptAt<=:now and (r.processingUntil is null or r.processingUntil<=:now) order by r.nextAttemptAt,r.id")
    List<Long> due(Instant now,Pageable page);
}
