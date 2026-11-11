package org.demo.paymentservice.followup;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface PaymentFollowUpRepository extends JpaRepository<PaymentFollowUp,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentFollowUp t where t.id=:id")
    Optional<PaymentFollowUp> lockId(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentFollowUp t where t.orderId=:orderId and t.kind=:kind")
    Optional<PaymentFollowUp> lockKind(Long orderId,FollowUpKind kind);
    Optional<PaymentFollowUp> findByOrderIdAndKind(Long orderId,FollowUpKind kind);
    @Query("select t.id from PaymentFollowUp t where t.status in :statuses and t.nextAttemptAt<=:now "
            +"and (t.processingUntil is null or t.processingUntil<=:now) order by t.nextAttemptAt,t.id")
    List<Long> due(List<FollowUpStatus> statuses,Instant now,Pageable page);
    @Query("select t.id from PaymentFollowUp t where t.orderId=:orderId order by t.id")
    List<Long> forOrder(Long orderId);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("update PaymentFollowUp t set t.status=org.demo.paymentservice.followup.FollowUpStatus.PENDING, t.nextAttemptAt=:now "
            +"where t.orderId=:orderId and t.status=org.demo.paymentservice.followup.FollowUpStatus.WAITING "
            +"and (t.kind<>org.demo.paymentservice.followup.FollowUpKind.PAYMENT_NOTIFICATION or not exists "
            +"(select i.id from PaymentFollowUp i where i.orderId=:orderId and i.kind=org.demo.paymentservice.followup.FollowUpKind.INVOICE_ISSUE))")
    int readyWaitingForOrder(Long orderId,Instant now);
    @Query("select t.orderId from PaymentFollowUp t where t.id=:id") Optional<Long> orderForTask(Long id);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("update PaymentFollowUp t set t.status=org.demo.paymentservice.followup.FollowUpStatus.CANCELLED, t.nextAttemptAt=null, "
            +"t.processingToken=null, t.processingUntil=null, t.lastErrorCode='CHECKOUT_EXPIRED', t.updatedAt=:now "
            +"where t.orderId=:orderId and t.status=org.demo.paymentservice.followup.FollowUpStatus.WAITING")
    int cancelWaitingForOrder(Long orderId,Instant now);
    @Query("select count(t) from PaymentFollowUp t where t.orderId=:orderId and t.processingUntil>:now")
    long activeForOrder(Long orderId,Instant now);
    @Query("select count(t) from PaymentFollowUp t where t.orderId=:orderId and t.status=org.demo.paymentservice.followup.FollowUpStatus.SUCCEEDED")
    long completedForOrder(Long orderId);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("update PaymentFollowUp t set t.status=org.demo.paymentservice.followup.FollowUpStatus.CANCELLED, t.nextAttemptAt=null, "
            +"t.processingToken=null, t.processingUntil=null, t.lastErrorCode='STRIPE_REFUND', t.updatedAt=:now "
            +"where t.orderId=:orderId and t.status<>org.demo.paymentservice.followup.FollowUpStatus.SUCCEEDED")
    int cancelForRefund(Long orderId,Instant now);
    @Query("select new org.demo.paymentservice.followup.FollowUpResponse(t.id,t.orderId,t.kind,t.status,t.attempts,"
            +"t.replayCount,t.nextAttemptAt,t.resultId,t.lastErrorCode,t.createdAt,t.updatedAt) "
            +"from PaymentFollowUp t where t.id<=:ceiling and (:status is null or t.status=:status) "
            +"and (:orderId is null or t.orderId=:orderId) order by t.id desc")
    List<FollowUpResponse> page(long ceiling,FollowUpStatus status,Long orderId,Pageable page);
}
