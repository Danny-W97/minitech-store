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
    @Query("select s from OrderSaga s where s.id <= :ceiling and (:state is null or s.state=:state) order by s.id desc")
    List<OrderSaga> page(@Param("ceiling") long ceiling,@Param("state") OrderSagaState state,org.springframework.data.domain.Pageable page);
    @Query("select s.orderId from OrderSaga s where s.state in :states and s.nextAttemptAt<=:now and (s.processingUntil is null or s.processingUntil<=:now) order by s.nextAttemptAt,s.id")
    List<Long> due(@Param("states") Collection<OrderSagaState> states,@Param("now") Instant now,org.springframework.data.domain.Pageable page);

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
