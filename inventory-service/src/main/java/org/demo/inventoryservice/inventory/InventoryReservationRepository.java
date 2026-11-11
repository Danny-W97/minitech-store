package org.demo.inventoryservice.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

    Optional<InventoryReservation> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InventoryReservation r where r.id = :id")
    Optional<InventoryReservation> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InventoryReservation r where r.orderId = :orderId")
    Optional<InventoryReservation> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    // IDs avoid retaining a stale managed reservation before acquiring its lock.
    @Query("select r.id from InventoryReservation r where r.status = :status "
            + "and r.expiresAt < :now order by r.id")
    List<Long> findExpiredIds(@Param("status") ReservationStatus status,
                            @Param("now") Instant now, Pageable pageable);
}
