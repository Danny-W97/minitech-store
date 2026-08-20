package org.demo.orderservice.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, Long> {

    Optional<OrderSaga> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select saga from OrderSaga saga where saga.orderId = :orderId")
    Optional<OrderSaga> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OrderSaga> findTop100ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Collection<OrderSagaState> states,
            Instant now
    );
}
