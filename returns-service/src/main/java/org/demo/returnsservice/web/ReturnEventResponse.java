package org.demo.returnsservice.web;
import org.demo.returnsservice.returns.ReturnEvent;
import java.time.Instant;
import java.util.UUID;
public record ReturnEventResponse(Long id,long revision,String action,String fromStatus,String toStatus,String note,String actorRef,
        String orderStatus,String paymentState,UUID operationId,Instant createdAt) {
    public static ReturnEventResponse from(ReturnEvent e) {
        return new ReturnEventResponse(e.getId(),e.getRevision(),e.getAction(),e.getFromStatus(),e.getToStatus(),e.getNote(),e.getActorRef(),e.getOrderStatus(),e.getPaymentState(),e.getOperationId(),e.getCreatedAt());
    }
}
