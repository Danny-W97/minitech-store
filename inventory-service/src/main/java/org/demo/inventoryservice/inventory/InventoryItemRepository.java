package org.demo.inventoryservice.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    boolean existsByProductId(Long productId);

    Optional<InventoryItem> findByProductId(Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from InventoryItem item where item.productId = :productId")
    Optional<InventoryItem> findByProductIdForUpdate(@Param("productId") Long productId);
}
