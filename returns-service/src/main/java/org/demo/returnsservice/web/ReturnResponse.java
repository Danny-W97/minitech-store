package org.demo.returnsservice.web;

import org.demo.returnsservice.returns.ReturnRequest;
import org.demo.returnsservice.returns.ReturnStatus;
import org.demo.returnsservice.returns.ReturnType;
import java.time.Instant;

public record ReturnResponse(Long id, Long orderId, Long customerId, String customerEmail, ReturnType requestType,
                             String reason, String description, ReturnStatus status, String moderationNote,
                             Instant createdAt, Instant updatedAt,long version,boolean requiresReconciliation,String effectsState,java.util.List<Item> items) {
    @io.swagger.v3.oas.annotations.media.Schema(name="ReturnCaseItem")
    public record Item(Long orderItemId,Long productId,String productName,int orderedQuantity,int quantity) {}
    public static ReturnResponse from(ReturnRequest r) {
        return new ReturnResponse(r.getId(), r.getOrderId(), r.getCustomerId(), r.getCustomerEmail(), r.getRequestType(),
                r.getReason(), r.getDescription(), r.getStatus(), r.getModerationNote(), r.getCreatedAt(), r.getUpdatedAt(),r.getVersion(),r.getOrderVerifiedAt()==null,
                r.getOrderVerifiedAt()==null?"LEGACY_UNVERIFIED":r.getStatus()==ReturnStatus.APPROVED?"DOMAIN_RECONCILIATION_REQUIRED":r.getStatus()==ReturnStatus.COMPLETED?"DOMAIN_RECONCILED":r.getStatus()==ReturnStatus.REJECTED?"NOT_APPLICABLE":"NOT_REQUESTED",
                r.getItems().stream().map(i -> new Item(i.getOrderItemId(),i.getProductId(),i.getProductName(),i.getOrderedQuantity(),i.getQuantity())).toList());
    }
}
