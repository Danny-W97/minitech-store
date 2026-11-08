package org.demo.paymentservice.payment;

import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PaymentOrderGateRepository extends JpaRepository<PaymentOrderGate,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PaymentOrderGate g where g.orderId=:id")
    Optional<PaymentOrderGate> lock(Long id);
}
