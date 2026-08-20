package org.demo.returnsservice.web;

import org.demo.returnsservice.returns.ReturnRequest;
import org.demo.returnsservice.returns.ReturnStatus;
import org.demo.returnsservice.returns.ReturnType;
import java.time.Instant;

public record ReturnResponse(Long id, Long orderId, Long customerId, String customerEmail, ReturnType requestType,
                             String reason, String description, ReturnStatus status, String moderationNote,
                             Instant createdAt, Instant updatedAt) {
    public static ReturnResponse from(ReturnRequest r) {
        return new ReturnResponse(r.getId(), r.getOrderId(), r.getCustomerId(), r.getCustomerEmail(), r.getRequestType(),
                r.getReason(), r.getDescription(), r.getStatus(), r.getModerationNote(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
