package org.demo.inventoryservice.inventory;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface StockOperationRepository extends JpaRepository<StockOperation,Long> {
    Optional<StockOperation> findByOperationId(String operationId);
    @Query("select s from StockOperation s where s.id<=:ceiling and (:productId is null or s.productId=:productId) order by s.id desc")
    List<StockOperation> page(@Param("ceiling") long ceiling,@Param("productId") Long productId,org.springframework.data.domain.Pageable page);
}
