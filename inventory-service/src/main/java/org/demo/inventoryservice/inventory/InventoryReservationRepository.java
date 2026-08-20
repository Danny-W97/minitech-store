package org.demo.inventoryservice.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

    Optional<InventoryReservation> findByOrderId(Long orderId);

    List<InventoryReservation> findAllByStatusAndExpiresAtBefore(
            ReservationStatus status,
            Instant expiresAt
    );
}
