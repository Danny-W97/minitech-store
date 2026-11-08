package org.demo.paymentservice.payment;

import org.demo.paymentservice.outbox.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.UUID;

/** Only local state is touched inside these short transactions. */
@Service
public class PaymentStateStore {
    @org.springframework.beans.factory.annotation.Value("${messaging.payment-identity-v2.enabled:false}") private boolean identityV2;
    public record Ticket(Payment payment,String token) {}
    public record Lookup(Payment byOrder,Payment byKey) {}
    private final PaymentRepository payments;
    private final PaymentOutboxRepository events;
    private final org.demo.paymentservice.followup.PaymentFollowUpRepository followUps;
    private final StripeWebhookReceiptRepository stripeReceipts;
    private final long leaseMillis;
    @org.springframework.beans.factory.annotation.Autowired private PaymentOrderGateRepository gates;
    public PaymentStateStore(PaymentRepository payments,PaymentOutboxRepository events,
            org.demo.paymentservice.followup.PaymentFollowUpRepository followUps,
            StripeWebhookReceiptRepository stripeReceipts,
            @Value("${payment.processing-lease-ms:120000}") long leaseMillis) {
        if (leaseMillis<60000) throw new IllegalArgumentException("Payment lease must cover the request budget");
        this.payments=payments; this.events=events; this.followUps=followUps;this.stripeReceipts=stripeReceipts;this.leaseMillis=leaseMillis;
    }
    @Transactional(readOnly=true)
    public Lookup lookup(Long orderId,String key) {
        return new Lookup(payments.findByOrderId(orderId).orElse(null),payments.findByIdempotencyKey(key).orElse(null));
    }
    @Transactional
    public Ticket claim(Payment candidate) {
        if(gate(candidate.getOrderId()).isCancelled())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Order cancellation has fenced payment processing");
        var payment=payments.lockOrder(candidate.getOrderId()).orElse(candidate);
        payment.requireSameCommand(candidate.getRequestFingerprint(),candidate.getPaymentMethod());
        if (payment.resultRecorded()) return new Ticket(payment,null);
        if (payment.processing()) throw new PaymentInProgressException();
        if (payment.getAmount().compareTo(candidate.getAmount())!=0) throw new PaymentIdempotencyConflictException(candidate.getOrderId());
        // A recovered authorization must keep its original amount, method, key and reference.
        payment.bindRequest(candidate.getRequestFingerprint());
        String token=UUID.randomUUID().toString(); payment.claim(token,Instant.now().plusMillis(leaseMillis));
        payment=payments.save(payment); payments.flush(); return new Ticket(payment,token);
    }
    @Transactional(readOnly=true)
    public boolean stripeEventReceived(String eventId) { return stripeReceipts.existsById(eventId); }
    @Transactional
    public void recordIgnoredStripeEvent(String eventId,String eventType) {
        if(!stripeReceipts.existsById(eventId)) stripeReceipts.saveAndFlush(new StripeWebhookReceipt(eventId,eventType,null,"IGNORED"));
    }
    @Transactional
    public void recordCompletedStripeEvent(String eventId,String eventType,Long paymentId) {
        if(!stripeReceipts.existsById(eventId)) stripeReceipts.saveAndFlush(new StripeWebhookReceipt(eventId,eventType,paymentId,"PROCESSED"));
    }
    @Transactional
    public Ticket claimStripeCheckout(Long orderId,Long paymentId,String sessionId) {
        if(gate(orderId).isCancelled()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Order cancellation has fenced payment processing");
        var payment=payments.lockOrder(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe payment is not ready"));
        if(!payment.getId().equals(paymentId) || !sessionId.equals(payment.getCheckoutSessionId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stripe Checkout reference does not match this payment");
        if(payment.resultRecorded()) return new Ticket(payment,null);
        if(payment.getStatus()!=PaymentStatus.PENDING || payment.getCheckoutUrl()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Stripe payment is not pending Checkout");
        if(payment.processing()) throw new PaymentInProgressException();
        String token=UUID.randomUUID().toString();payment.claim(token,Instant.now().plusMillis(leaseMillis));
        payment=payments.saveAndFlush(payment);return new Ticket(payment,token);
    }
    @Transactional
    public Ticket claimStripeRecovery(Long paymentId,boolean paid) {
        var orderId=payments.orderIdForPayment(paymentId).orElseThrow();gate(orderId);
        var payment=payments.lockOrder(orderId).orElseThrow();
        if(payment.stripeExternalHold() || payment.getStatus()==PaymentStatus.REFUNDED || payment.getStatus()==PaymentStatus.SUCCEEDED
                || !paid && payment.resultRecorded()) return new Ticket(payment,null);
        if(payment.getCheckoutSessionId()==null || payment.processing()) throw new PaymentInProgressException();
        String token=UUID.randomUUID().toString();payment.claim(token,Instant.now().plusMillis(leaseMillis));
        payments.saveAndFlush(payment);return new Ticket(payment,token);
    }
    @Transactional
    public void recordStripePaid(Ticket ticket,String intent,String eventId,String eventType) {
        var current=requireOwner(ticket);current.recordStripePaid(intent);ticket.payment().recordStripePaid(intent);
        payments.saveAndFlush(current);
        if(eventId!=null && !stripeReceipts.existsById(eventId)) stripeReceipts.saveAndFlush(new StripeWebhookReceipt(eventId,eventType,current.getId(),"PROCESSED"));
    }
    @Transactional
    public void stripeRetry(Ticket ticket,String code) {
        var current=requireOwner(ticket);current.stripeRetry(code);
    }
    @Transactional public void stripeReview(Ticket ticket,String code) { requireOwner(ticket).stripeReview(code); }
    @Transactional public void stripePreflightReview(Long id,String code) {
        var orderId=payments.orderIdForPayment(id).orElseThrow();var current=payments.lockOrder(orderId).orElseThrow();
        if(!current.processing() && current.getCheckoutSessionId()!=null) current.stripeReview(code);
    }
    @Transactional public void observeExternalRefund(Long id,StripePaymentGateway.Session proof,StripePaymentGateway.Refund refund) {
        var orderId=payments.orderIdForPayment(id).orElseThrow();var payment=payments.lockOrder(orderId).orElseThrow();
        if(payment.processing()) throw new PaymentInProgressException();
        if(!orderId.equals(proof.orderId()) || !proof.id().equals(payment.getCheckoutSessionId()) || !"paid".equals(proof.paymentStatus())
                || payment.getAmount().movePointRight(2).longValueExact()!=proof.amount() || !payment.getCurrency().equalsIgnoreCase(proof.currency())
                || !proof.intent().equals(refund.intent()) || !payment.getCurrency().equalsIgnoreCase(refund.currency())
                || refund.amount()>proof.amount()) throw new StripeReviewRequiredException("EXTERNAL_REFUND_IDENTITY_CONFLICT");
        payment.recordStripePaid(proof.intent());
        if(refund.amount()==proof.amount() && "succeeded".equals(refund.status())) payment.markRefunded();
        payment.externalRefund(refund.id(),!java.util.Set.of("failed","canceled").contains(refund.status()));
    }
    @Transactional public void externalRefundResult(Long id,String refundId,boolean fullSuccess,String code,boolean pending) {
        var orderId=payments.orderIdForPayment(id).orElseThrow();var payment=payments.lockOrder(orderId).orElseThrow();
        if(!refundId.equals(payment.getStripeExternalRefundId())) return;
        if(fullSuccess) payment.markRefunded();
        if(pending) payment.stripeRetry(code);else payment.stripeReview(code);
    }
    @Transactional public boolean externalRefundProgress(Long id,String refundId,boolean fullSuccess) {
        var orderId=payments.orderIdForPayment(id).orElseThrow();var payment=payments.lockOrder(orderId).orElseThrow();
        if(!refundId.equals(payment.getStripeExternalRefundId())) return false;
        if(fullSuccess) payment.markRefunded();
        payment.externalRefund(refundId,true);return true;
    }
    @Transactional public void stripeCancellationResolved(Long id) {
        var orderId=payments.orderIdForPayment(id).orElseThrow();var current=payments.lockOrder(orderId).orElseThrow();
        if(current.getStatus()==PaymentStatus.FAILED && current.isProviderDeclined()) current.stripeResolved();
    }
    @Transactional
    public void fenceCancellation(Long orderId) {
        var gate=gate(orderId);
        if(gate.isCancelled()) return;
        var payment=payments.lockOrder(orderId).orElse(null);
        // Pending/expired attempts may already have reached the provider.
        if(payment!=null && (payment.getStatus()!=PaymentStatus.FAILED || !payment.resultRecorded()
                || !payment.isProviderDeclined() || payment.getProviderReference()!=null || payment.processing()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment or fulfillment requires an explicit refund/compensation process");
        gate.cancel();gates.flush();
    }
    private PaymentOrderGate gate(Long orderId) {
        return gates.lock(orderId).orElseGet(() -> gates.saveAndFlush(new PaymentOrderGate(orderId)));
    }
    @Transactional
    public Payment finish(Ticket ticket,PaymentOutboxEvent event) {
        return finish(ticket,event,java.util.List.of());
    }
    @Transactional
    public Payment finish(Ticket ticket,PaymentOutboxEvent event,java.util.List<org.demo.paymentservice.followup.PaymentFollowUp> tasks) {
        requireOwner(ticket);
        if(!tasks.isEmpty() && (ticket.payment().getStatus()!=PaymentStatus.SUCCEEDED
                && !(ticket.payment().getStatus()==PaymentStatus.PENDING && PaymentMethod.parse(ticket.payment().getPaymentMethod()).deferred())
                || tasks.stream().anyMatch(task -> !ticket.payment().getOrderId().equals(task.getOrderId()))))
            throw new IllegalArgumentException("Follow-ups require this payment's success");
        ticket.payment().release(); ticket.payment().recordResult();
        var saved=payments.saveAndFlush(ticket.payment());
        followUps.saveAll(tasks);
        saveCompatibleEvent(event);
        return saved;
    }
    @Transactional
    public void recordAuthorization(Ticket ticket,String reference) {
        var current=requireOwner(ticket);
        current.recordAuthorization(reference);
        ticket.payment().recordAuthorization(reference);
    }
    @Transactional
    public void recordStripeCheckout(Ticket ticket,String sessionId,String checkoutUrl,
            java.util.List<org.demo.paymentservice.followup.PaymentFollowUp> tasks) {
        var current=requireOwner(ticket);
        if(ticket.payment().getStatus()!=PaymentStatus.PENDING || PaymentMethod.parse(ticket.payment().getPaymentMethod())!=PaymentMethod.CARD
                || tasks.stream().anyMatch(task -> !ticket.payment().getOrderId().equals(task.getOrderId())
                        || task.getStatus()!=org.demo.paymentservice.followup.FollowUpStatus.WAITING))
            throw new IllegalArgumentException("Stripe Checkout must stage only this card payment's waiting follow-ups");
        current.recordCheckout(sessionId,checkoutUrl);ticket.payment().recordCheckout(sessionId,checkoutUrl);
        payments.saveAndFlush(current);followUps.saveAll(tasks);
    }
    @Transactional
    public Payment finishStripeCheckout(Ticket ticket,PaymentOutboxEvent event,String eventId,String eventType) {
        requireOwner(ticket);
        if(ticket.payment().getStatus()!=PaymentStatus.SUCCEEDED || ticket.payment().getCheckoutSessionId()==null)
            throw new IllegalArgumentException("Stripe Checkout result was not verified");
        ticket.payment().release();ticket.payment().recordResult();
        ticket.payment().stripeResolved();
        var saved=payments.saveAndFlush(ticket.payment());
        followUps.readyWaitingForOrder(saved.getOrderId(),Instant.now());
        saveCompatibleEvent(event);
        if(eventId!=null && !stripeReceipts.existsById(eventId)) stripeReceipts.saveAndFlush(new StripeWebhookReceipt(eventId,eventType,saved.getId(),"PROCESSED"));
        return saved;
    }
    @Transactional
    public Payment finishExpiredStripeCheckout(Ticket ticket,PaymentOutboxEvent event,String eventId,String eventType) {
        requireOwner(ticket);
        if(ticket.payment().getStatus()!=PaymentStatus.FAILED || !ticket.payment().isProviderDeclined()
                || ticket.payment().getCheckoutSessionId()==null)
            throw new IllegalArgumentException("Expired Stripe Checkout was not safely marked declined");
        ticket.payment().release();ticket.payment().recordResult();
        ticket.payment().stripeRetry("CANCELLATION_PENDING");
        var saved=payments.saveAndFlush(ticket.payment());
        followUps.cancelWaitingForOrder(saved.getOrderId(),Instant.now());
        saveCompatibleEvent(event);
        if(eventId!=null && !stripeReceipts.existsById(eventId)) stripeReceipts.saveAndFlush(new StripeWebhookReceipt(eventId,eventType,saved.getId(),"PROCESSED"));
        return saved;
    }
    @Transactional
    public Payment collect(Long id,org.demo.paymentservice.web.CollectPaymentRequest receipt) {
        // Load only the scalar before the lock: a managed pre-lock Payment could
        // otherwise keep an obsolete PENDING snapshot after another replica commits.
        var orderId=payments.orderIdForPayment(id).orElseThrow(() -> new PaymentNotFoundException(id));
        var payment=payments.lockOrder(orderId).orElseThrow();
        if(!PaymentMethod.parse(payment.getPaymentMethod()).deferred() || receipt.amount()==null
                || payment.getAmount().compareTo(receipt.amount())!=0 || !payment.getCurrency().equals(receipt.currency()))
            throw new PaymentIdempotencyConflictException(payment.getOrderId());
        if(payment.getCollectionReference()!=null) {
            if(!payment.getCollectionReference().equals(receipt.receiptReference())) throw new PaymentIdempotencyConflictException(payment.getOrderId());
            return payment;
        }
        if(payment.getStatus()!=PaymentStatus.PENDING || !payment.resultRecorded() || payment.processing())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Deferred payment is not ready for collection");
        var settlement=followUps.lockKind(payment.getOrderId(),org.demo.paymentservice.followup.FollowUpKind.ORDER_SETTLEMENT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"Deferred fulfillment must be accepted first"));
        if(settlement.getStatus()!=org.demo.paymentservice.followup.FollowUpStatus.WAITING)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Settlement state conflicts with collection");
        payment.collect(receipt.receiptReference());payment.recordResult();settlement.ready();
        return payments.saveAndFlush(payment);
    }
    @Transactional(readOnly=true)
    public void assertOwner(Ticket ticket) {
        var current=payments.findByOrderId(ticket.payment().getOrderId()).orElseThrow();
        checkOwner(ticket,current);
    }
    @Transactional
    public void release(Ticket ticket) {
        if (ticket==null || ticket.token()==null) return;
        var current=payments.lockOrder(ticket.payment().getOrderId()).orElseThrow();
        if (!ticket.token().equals(current.getProcessingToken())) return;
        current.release();
        if (ticket.payment().getStatus()==PaymentStatus.FAILED) current.markFailed(ticket.payment().getFailureReason());
        payments.save(current);
    }
    private Payment requireOwner(Ticket ticket) {
        var current=payments.lockOrder(ticket.payment().getOrderId()).orElseThrow();
        checkOwner(ticket,current);
        return current;
    }
    private void checkOwner(Ticket ticket,Payment current) {
        if (!ticket.token().equals(current.getProcessingToken()) || !current.processing()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment processing lease was replaced or expired");
    }

    private void saveCompatibleEvent(org.demo.paymentservice.outbox.PaymentOutboxEvent event){
        if(event==null)return;String legacy=event.getEventKey();
        if(events.findByEventKey(legacy).isPresent()||events.findByEventKey("v2:"+org.demo.platformevents.EventIdentity.hash(legacy)).isPresent()||events.findByEventKey("retained:"+org.demo.platformevents.EventIdentity.hash(legacy)).isPresent())return;
        if(identityV2)event.useNewIdentity();events.save(event);
    }
}
