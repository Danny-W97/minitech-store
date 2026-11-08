package org.demo.inventoryservice.web;

import org.demo.inventoryservice.inventory.StockOperation;
import java.time.Instant;

public record StockOperationResponse(Long id,String operationId,Long productId,String kind,String status,int quantity,
        int availableBefore,int availableQuantity,int reservedQuantity,int soldQuantity,long beforeVersion,long version,Instant createdAt) {
    public static StockOperationResponse from(StockOperation operation) {
        return new StockOperationResponse(operation.getId(),operation.getOperationId(),operation.getProductId(),operation.getKind(),"COMMITTED",
                operation.getQuantity(),operation.getAvailableBefore(),operation.getAvailableQuantity(),operation.getReservedQuantity(),
                operation.getSoldQuantity(),operation.getBeforeVersion(),operation.getVersion(),operation.getCreatedAt());
    }
}
