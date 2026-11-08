package org.demo.paymentservice.followup;

import java.time.Instant;

/** Operator diagnostics deliberately exclude recipient, addresses and the saved invoice command. */
public record FollowUpResponse(Long id,Long orderId,FollowUpKind kind,FollowUpStatus status,int attempts,
        int replayCount,Instant nextAttemptAt,Long resultId,String lastErrorCode,Instant createdAt,Instant updatedAt) {
    public static FollowUpResponse from(PaymentFollowUp task) {
        return new FollowUpResponse(task.getId(),task.getOrderId(),task.getKind(),task.getStatus(),task.getAttempts(),
                task.getReplayCount(),task.getNextAttemptAt(),task.getResultId(),task.getLastErrorCode(),task.getCreatedAt(),task.getUpdatedAt());
    }
}
