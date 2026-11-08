package org.demo.deliveryservice.delivery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByOrderId(Long orderId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Shipment s where s.orderId=:id")
    Optional<Shipment> lockOrder(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Query("select s.orderId from Shipment s where s.syncState in ('PENDING','RETRY') and s.syncNextAttemptAt<=:now and (s.syncUntil is null or s.syncUntil<=:now) order by s.syncNextAttemptAt,s.id")
    java.util.List<Long> due(java.time.Instant now,org.springframework.data.domain.Pageable page);
}
