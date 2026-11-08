package org.demo.paymentservice.payment;

import org.demo.paymentservice.followup.PaymentFollowUpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class PaymentRefundStateStore {
    public record Ticket(PaymentRefund refund,String token) {}
    private final PaymentRefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentFollowUpRepository tasks;
    public PaymentRefundStateStore(PaymentRefundRepository refunds,PaymentRepository payments,PaymentFollowUpRepository tasks) {
        this.refunds=refunds;this.payments=payments;this.tasks=tasks;
    }
    @Transactional
    public PaymentRefund request(Long paymentId,String key,BigDecimal amount,boolean automatic,String reason,String actor,
            StripePaymentGateway.Session proof,String ownerToken) {
        var orderId=payments.orderIdForPayment(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        var payment=payments.lockOrder(orderId).orElseThrow();
        var previous=refunds.findByPaymentId(paymentId).orElse(null);
        if(previous!=null) {
            if(!automatic && (!key.equals(previous.getRequestKey()) || amount.compareTo(previous.getAmount())!=0 || !reason.equals(previous.getReason()))) throw conflict();
            return previous;
        }
        boolean returnSettlement="service:returns-service".equals(actor);
        if(key==null || key.isBlank() || key.length()>100 || refunds.findByRequestKey(key).isPresent()
                || amount==null || (returnSettlement ? (amount.compareTo(BigDecimal.ZERO)<=0 || amount.compareTo(payment.getAmount())>0) : amount.compareTo(payment.getAmount())!=0)
                || payment.getCheckoutSessionId()==null || payment.stripeExternalHold()
                || payment.getStatus()==PaymentStatus.REFUNDED || !payment.getOrderId().equals(proof.orderId())
                || !payment.getCheckoutSessionId().equals(proof.id()) || !"paid".equals(proof.paymentStatus())
                || payment.getAmount().movePointRight(2).longValueExact()!=proof.amount() || !payment.getCurrency().equalsIgnoreCase(proof.currency())
                || payment.processing() && !Objects.equals(ownerToken,payment.getProcessingToken())
                || tasks.activeForOrder(orderId,Instant.now())>0) throw conflict();
        if(automatic && (payment.getStatus()==PaymentStatus.SUCCEEDED || tasks.completedForOrder(orderId)>0)) throw conflict();
        payment.recordStripePaid(proof.intent());payment.stripeResolved();
        var refund=refunds.saveAndFlush(new PaymentRefund(payment,key,amount,automatic,reason,actor));
        return refund;
    }
    @Transactional(readOnly=true) public PaymentRefund get(Long id) {
        return refunds.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Refund not found"));
    }
    @Transactional(readOnly=true) public Optional<PaymentRefund> byPayment(Long id) { return refunds.findByPaymentId(id); }
    @Transactional(readOnly=true) public List<Long> due() { return refunds.due(Instant.now(),org.springframework.data.domain.PageRequest.of(0,20)); }
    @Transactional
    public Ticket claim(Long id) {
        var refund=refunds.lockId(id).orElseThrow();
        if(refund.processing() || refund.isBusinessCompleted() || refund.getStatus()==PaymentRefund.Status.REVIEW_REQUIRED || refund.getStatus()==PaymentRefund.Status.FAILED) return null;
        String token=UUID.randomUUID().toString();refund.claim(token);refunds.flush();return new Ticket(refund,token);
    }
    @Transactional public void submitted(Ticket ticket) { owned(ticket).submitted(); }
    @Transactional public void freezeFulfillment(Ticket ticket) {
        var refund=owned(ticket);payments.lockOrder(refund.getOrderId()).orElseThrow();
        if(tasks.activeForOrder(refund.getOrderId(),Instant.now())>0) throw conflict();
        tasks.cancelForRefund(refund.getOrderId(),Instant.now());
    }
    @Transactional public void rejectEligibility(Ticket ticket,String code) { owned(ticket).rejectEligibility(code); }
    @Transactional
    public void provider(Ticket ticket,StripePaymentGateway.Refund result) {
        var refund=owned(ticket);refund.provider(result);
        if(refund.getStatus()==PaymentRefund.Status.SUCCEEDED) {
            var payment=payments.lockOrder(refund.getOrderId()).orElseThrow();
            payment.markRefunded();payments.saveAndFlush(payment);tasks.cancelForRefund(refund.getOrderId(),Instant.now());
        }
    }
    @Transactional public void complete(Ticket ticket,String outcome) { owned(ticket).businessCompleted(outcome); }
    @Transactional public void retry(Ticket ticket,String code) { owned(ticket).retry(code); }
    @Transactional public void review(Ticket ticket,String code) { owned(ticket).review(code); }
    @Transactional public void release(Ticket ticket) {
        var current=refunds.lockId(ticket.refund().getId()).orElseThrow();
        if(ticket.token().equals(current.getProcessingToken())) current.release();
    }
    @Transactional public void reconcile(Long id) {
        var refund=refunds.lockId(id).orElseThrow();if(refund.processing()) throw conflict();
        if(refund.rejectedWithoutMoney()) {
            payments.lockOrder(refund.getOrderId()).orElseThrow();
            if(tasks.activeForOrder(refund.getOrderId(),Instant.now())>0 || refund.isAutomatic() && tasks.completedForOrder(refund.getOrderId())>0) throw conflict();
        }
        refund.reconciliate();
    }
    private PaymentRefund owned(Ticket ticket) {
        var refund=refunds.lockId(ticket.refund().getId()).orElseThrow();
        if(!ticket.token().equals(refund.getProcessingToken()) || !refund.processing()) throw conflict();return refund;
    }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Refund conflicts with an existing operation, amount, or active fulfillment; reconciliation is required"); }
}
