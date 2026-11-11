package org.demo.paymentservice.followup;

import org.demo.paymentservice.outbox.*;
import org.demo.platformsecurity.ListWindow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Service
public class FollowUpStateStore {
    @org.springframework.beans.factory.annotation.Value("${messaging.payment-identity-v2.enabled:false}") private boolean identityV2;
    public record Ticket(Long id,Long orderId,FollowUpKind kind,String payload,String token) {}
    private final PaymentFollowUpRepository tasks;
    private final PaymentOutboxRepository events;
    private final ObjectMapper json;
    private final org.demo.paymentservice.payment.PaymentRepository payments;
    @org.springframework.beans.factory.annotation.Autowired private org.demo.paymentservice.payment.PaymentRefundRepository refunds;
    private final long leaseMillis;
    private final int maxAttempts;
    public FollowUpStateStore(PaymentFollowUpRepository tasks,PaymentOutboxRepository events,ObjectMapper json,
            org.demo.paymentservice.payment.PaymentRepository payments,
            @Value("${payment.follow-ups.lease-ms:45000}") long leaseMillis,
            @Value("${payment.follow-ups.max-attempts:20}") int maxAttempts) {
        if(leaseMillis<30000 || leaseMillis>3600000 || maxAttempts<1 || maxAttempts>100)
            throw new IllegalArgumentException("Invalid payment follow-up limits");
        this.tasks=tasks;this.events=events;this.json=json;this.payments=payments;this.leaseMillis=leaseMillis;this.maxAttempts=maxAttempts;
    }
    @Transactional(readOnly=true) public List<Long> due() {
        return tasks.due(List.of(FollowUpStatus.PENDING,FollowUpStatus.RETRY),Instant.now(),org.springframework.data.domain.PageRequest.of(0,50));
    }
    @Transactional(readOnly=true) public List<Long> forOrder(Long order) { return tasks.forOrder(order); }
    public record Collection(Long paymentId,java.math.BigDecimal amount,String currency,String reference) {}
    @Transactional(readOnly=true) public Collection collection(Long order) {
        var payment=payments.findByOrderId(order).orElseThrow();
        if(payment.getStatus()!=org.demo.paymentservice.payment.PaymentStatus.SUCCEEDED || payment.getCollectionReference()==null)
            throw new IllegalStateException("Cash receipt has not been recorded");
        return new Collection(payment.getId(),payment.getAmount(),payment.getCurrency(),payment.getCollectionReference());
    }
    @Transactional(readOnly=true) public boolean owns(Ticket ticket) {
        var current=tasks.findById(ticket.id()).orElseThrow();
        return ticket.token().equals(current.getProcessingToken()) && current.processing();
    }
    @Transactional public Ticket claim(Long id) {
        var orderId=tasks.orderForTask(id).orElseThrow();
        var payment=payments.lockOrder(orderId).orElseThrow();
        if(payment.stripeExternalHold()) return null;
        if(refunds.findByPaymentId(payment.getId()).filter(row -> !row.rejectedWithoutMoney()).isPresent()) return null;
        var task=tasks.lockId(id).orElseThrow();
        if(!List.of(FollowUpStatus.PENDING,FollowUpStatus.RETRY).contains(task.getStatus())
                || task.processing() || task.getNextAttemptAt().isAfter(Instant.now())) return null;
        if(task.getAttempts()>=maxAttempts) { task.fail("ATTEMPT_LIMIT",true);return null; }
        String token=UUID.randomUUID().toString();task.claim(token,Instant.now().plusMillis(leaseMillis));
        return new Ticket(id,task.getOrderId(),task.getKind(),task.getPayload(),token);
    }
    @Transactional public void complete(Ticket ticket,Long resultId) {
        var task=owned(ticket);if(task==null) return;
        if(ticket.kind()==FollowUpKind.INVOICE_ISSUE && (resultId==null || resultId<1)) throw new IllegalArgumentException("Invoice ID required");
        if(ticket.kind()==FollowUpKind.PAYMENT_NOTIFICATION || ticket.kind()==FollowUpKind.PAYMENT_PENDING_NOTIFICATION) {
            var notice=json.readValue(ticket.payload(),FollowUpPlanner.Notice.class);
            if(!task.getOrderId().equals(notice.orderId())) throw new IllegalArgumentException("Notification order mismatch");
            Long invoice=null;
            if(notice.invoiceRequested()) {
                var dependency=tasks.findByOrderIdAndKind(ticket.orderId(),FollowUpKind.INVOICE_ISSUE).orElseThrow();
                if(dependency.getStatus()!=FollowUpStatus.SUCCEEDED || dependency.getResultId()==null)
                    throw new IllegalStateException("Invoice dependency is incomplete");
                invoice=dependency.getResultId();
            }
            var event=new PaymentOutboxEvent(ticket.kind()==FollowUpKind.PAYMENT_PENDING_NOTIFICATION ? "PAYMENT_PENDING" : "PAYMENT_SUCCEEDED",notice.orderId(),notice.amount(),notice.recipient(),notice.address(),notice.correlationId(),invoice);
            saveCompatibleEvent(event);
        }
        task.succeed(resultId);
        if(ticket.kind()==FollowUpKind.ORDER_SETTLEMENT) {
            var invoice=tasks.lockKind(ticket.orderId(),FollowUpKind.INVOICE_ISSUE);
            invoice.filter(waiter -> waiter.getStatus()==FollowUpStatus.WAITING).ifPresent(PaymentFollowUp::ready);
            if(invoice.isEmpty() || invoice.get().getStatus()==FollowUpStatus.SUCCEEDED)
                tasks.lockKind(ticket.orderId(),FollowUpKind.PAYMENT_NOTIFICATION)
                        .filter(waiter -> waiter.getStatus()==FollowUpStatus.WAITING).ifPresent(PaymentFollowUp::ready);
        }
        if(ticket.kind()==FollowUpKind.INVOICE_ISSUE) tasks.lockKind(ticket.orderId(),FollowUpKind.PAYMENT_NOTIFICATION)
                .filter(waiter -> waiter.getStatus()==FollowUpStatus.WAITING).ifPresent(PaymentFollowUp::ready);
    }
    @Transactional public void fail(Ticket ticket,String code,boolean permanent) {
        var task=owned(ticket);if(task!=null) task.fail(code,permanent || task.getAttempts()>=maxAttempts);
    }
    @Transactional public FollowUpResponse replay(Long id) {
        var orderId=tasks.orderForTask(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Follow-up not found"));
        var payment=payments.lockOrder(orderId).orElseThrow();
        if(payment.stripeExternalHold()) throw new ResponseStatusException(HttpStatus.CONFLICT,"External Stripe refund has fenced fulfillment replay");
        if(refunds.findByPaymentId(payment.getId()).filter(row -> !row.rejectedWithoutMoney()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Refund has fenced fulfillment replay");
        var task=tasks.lockId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Follow-up not found"));
        if(task.getStatus()!=FollowUpStatus.DEAD_LETTER || task.processing())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a dead-letter task can be replayed");
        task.replay();return FollowUpResponse.from(task);
    }
    @Transactional(readOnly=true) public ListWindow.ListPage<FollowUpResponse> list(FollowUpStatus status,Long order,ListWindow window) {
        if(order!=null && order<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"orderId must be positive");
        return window.map(tasks.page(window.ceiling(),status,order,window.fetch()),FollowUpResponse::id,java.util.function.Function.identity());
    }
    private PaymentFollowUp owned(Ticket ticket) {
        var current=tasks.lockId(ticket.id()).orElseThrow();
        return ticket.token().equals(current.getProcessingToken()) && current.processing() ? current : null;
    }

    private void saveCompatibleEvent(org.demo.paymentservice.outbox.PaymentOutboxEvent event){
        if(event==null)return;String legacy=event.getEventKey();
        if(events.findByEventKey(legacy).isPresent()||events.findByEventKey("v2:"+org.demo.platformevents.EventIdentity.hash(legacy)).isPresent()||events.findByEventKey("retained:"+org.demo.platformevents.EventIdentity.hash(legacy)).isPresent())return;
        if(identityV2)event.useNewIdentity();events.save(event);
    }
}
