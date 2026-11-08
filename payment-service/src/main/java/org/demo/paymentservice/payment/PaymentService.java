package org.demo.paymentservice.payment;

import org.demo.paymentservice.inventory.InventoryClient;
import org.demo.paymentservice.inventory.InventoryServiceException;
import org.demo.paymentservice.order.OrderClient;
import org.demo.paymentservice.order.OrderResponse;
import org.demo.paymentservice.order.OrderServiceException;
import org.demo.paymentservice.outbox.PaymentOutboxEvent;
import org.demo.platformobservability.CorrelationIdContext;
import org.demo.paymentservice.followup.FollowUpPlanner;
import org.demo.paymentservice.followup.FollowUpRunner;
import org.demo.paymentservice.web.CreatePaymentRequest;
import org.demo.paymentservice.web.PaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.RoundingMode;
import java.util.List;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final OrderClient orderClient;
    private final InventoryClient inventoryClient;
    private final FollowUpPlanner followUps;
    private final FollowUpRunner followUpRunner;
    private final PaymentGateway paymentGateway;
    private final PaymentStateStore states;
    private final org.demo.paymentservice.promotion.PromotionClient promotionClient;

    @Value("${payment.inventory-confirm-attempts:3}")
    private int inventoryConfirmAttempts = 3;

    @Value("${payment.inventory-confirm-backoff-ms:250}")
    private long inventoryConfirmBackoffMs = 250;

    @Value("${payment.concurrent-wait-ms:5000}")
    private long concurrentWaitMs = 5000;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderClient orderClient,
            InventoryClient inventoryClient,
            FollowUpPlanner followUps,
            FollowUpRunner followUpRunner,
            PaymentGateway paymentGateway, PaymentStateStore states, org.demo.paymentservice.promotion.PromotionClient promotionClient
    ) {
        this.paymentRepository = paymentRepository;
        this.orderClient = orderClient;
        this.inventoryClient = inventoryClient;
        this.followUps=followUps;
        this.followUpRunner=followUpRunner;
        this.paymentGateway = paymentGateway;
        this.states = states;
        this.promotionClient=promotionClient;
    }

    public org.demo.paymentservice.web.PaymentConfigResponse configuration() {
        return new org.demo.paymentservice.web.PaymentConfigResponse(paymentGateway.provider(),
                paymentGateway.supportedMethods().stream().map(Enum::name).sorted().toList());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PaymentResponse create(CreatePaymentRequest request) { return create(request,null); }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PaymentResponse create(CreatePaymentRequest request,String headerIdempotencyKey) {
        try (var ignored=org.demo.platformsecurity.RequestBudget.open(30000)) {
            long until=System.nanoTime()+Math.max(0,Math.min(10000,concurrentWaitMs))*1_000_000;
            while (true) {
                try { return process(request,headerIdempotencyKey); }
                catch (PaymentInProgressException busy) {
                    if (System.nanoTime()>=until) throw busy;
                    org.demo.platformsecurity.RequestBudget.check();
                    try { Thread.sleep(50); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw busy;
                    }
                }
            }
        }
    }

    private PaymentResponse process(CreatePaymentRequest request,String headerIdempotencyKey) {
        org.demo.platformsecurity.RequestBudget.check();
        PaymentMethod method=PaymentMethod.parse(request.paymentMethod());
        String key=resolveIdempotencyKey(request,headerIdempotencyKey);
        String fingerprint=PaymentCommand.fingerprint(request,method);
        var lookup=states.lookup(request.orderId(),key);
        var existing=lookup.byOrder();
        var byKey=lookup.byKey();
        if (byKey!=null && !byKey.getOrderId().equals(request.orderId())) throw new PaymentIdempotencyConflictException(request.orderId());
        if (existing==null) existing=byKey;
        if (existing!=null) {
            existing.requireSameCommand(fingerprint,method.name());
            if (existing.resultRecorded()) return PaymentResponse.from(existing);
            if (existing.getCheckoutUrl()!=null) return PaymentResponse.from(existing);
            if (existing.processing()) throw new PaymentInProgressException();
        }
        if(!method.deferred()) paymentGateway.requireAvailable();
        if(!method.deferred() && !paymentGateway.supportedMethods().contains(method))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Payment method is not supported by the configured provider");
        var order=orderClient.getOrder(request.orderId());
        if(order.lifecycleReviewRequired()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,"Order lifecycle requires reconciliation before payment");
        boolean recovered="PAID".equalsIgnoreCase(order.status());
        if (!recovered && !"CONFIRMED".equalsIgnoreCase(order.status())) throw new OrderNotPayableException(request.orderId(),order.status());
        var amount=order.totalPrice().setScale(2,RoundingMode.HALF_UP);
        if(!method.deferred()) paymentGateway.validatePayment(amount,"PLN",method);
        if(method.deferred()) {
            boolean matching=method==PaymentMethod.CASH_AT_PICKUP && "STORE_PICKUP".equals(order.deliveryMethod())
                    || method==PaymentMethod.CASH_ON_DELIVERY && "STANDARD_COURIER".equals(order.deliveryMethod());
            if(!matching || recovered) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Deferred method does not match this unpaid delivery");
        }
        if(order.promotionCode()!=null && !order.promotionCode().isBlank() && !Boolean.TRUE.equals(order.couponReservationVerified())) promotionClient.requireReservation(order);
        var candidate=new Payment(request.orderId(),amount,method.name(),existing==null ? key : existing.getIdempotencyKey());
        candidate.bindRequest(fingerprint);
        PaymentStateStore.Ticket ticket;
        try { ticket=states.claim(candidate); }
        catch (org.springframework.dao.DataIntegrityViolationException concurrentInsert) {
            // Re-read outside the failed transaction. A key race across different orders is a 409.
            var winner=states.lookup(request.orderId(),key);
            if (winner.byKey()!=null && !winner.byKey().getOrderId().equals(request.orderId())) throw new PaymentIdempotencyConflictException(request.orderId());
            ticket=states.claim(candidate);
        }
        var payment=ticket.payment();
        if (ticket.token()==null) return PaymentResponse.from(payment);
        try {
            if (method.deferred() && !recovered) {
                states.assertOwner(ticket);
                inventoryClient.confirmDeferred(order.id(),order.inventoryReservationId());
                states.assertOwner(ticket);
                order=orderClient.acceptDeferred(order.id(),payment.getId(),method.name(),amount,payment.getCurrency());
                if(!"COLLECTION_DUE".equals(order.paymentState()) || !payment.getId().equals(order.deferredPaymentId()))
                    throw new PaymentProcessingException("Order did not accept deferred fulfillment",null);
                var response=PaymentResponse.from(payment);
                var tasks=followUps.deferred(request,order,event(response,request.notificationRecipient(),order,null));
                var accepted=states.finish(ticket,null,tasks);followUpRunner.runOrder(order.id());
                return PaymentResponse.from(accepted);
            }
            String reference=payment.getProviderReference()!=null ? payment.getProviderReference() : "RECOVERED-ORDER-"+request.orderId();
            if (!recovered) {
                org.demo.platformsecurity.RequestBudget.check();
                states.assertOwner(ticket);
                if (payment.getProviderReference()==null) {
                    if(paymentGateway instanceof StripePaymentGateway && method==PaymentMethod.CARD)
                        inventoryClient.extendForCheckout(order.id(),order.inventoryReservationId(),java.time.Instant.now().plusSeconds(40*60));
                    var authorization=paymentGateway instanceof StripePaymentGateway stripe
                            ? stripe.authorize(request.orderId(),amount,payment.getCurrency(),method,payment.getIdempotencyKey(),payment.getCreatedAt().plusSeconds(31*60))
                            : paymentGateway.authorize(request.orderId(),amount,payment.getCurrency(),method,payment.getIdempotencyKey(),request.simulateFailure());
                    states.assertOwner(ticket);
                    if (!authorization.approved()) {
                        payment.markDeclined(authorization.failureReason());
                        var response=PaymentResponse.from(payment);
                        var declined=states.finish(ticket,event(response,request.notificationRecipient(),order,null));
                        compensateOrder(order);
                        return PaymentResponse.from(declined);
                    }
                    if (authorization.reference()==null || authorization.reference().isBlank()) throw new PaymentProcessingException("Payment provider returned no authorization reference",null);
                    if(authorization.checkoutUrl()!=null) {
                        var response=PaymentResponse.from(payment);
                        var pendingTasks=followUps.awaitingStripeCheckout(request,order,event(response,request.notificationRecipient(),order,null));
                        states.recordStripeCheckout(ticket,authorization.reference(),authorization.checkoutUrl(),pendingTasks);
                        return PaymentResponse.from(payment);
                    }
                    states.recordAuthorization(ticket,authorization.reference());
                }
                reference=payment.getProviderReference();
                try {
                    states.assertOwner(ticket);
                    var updated=orderClient.markPaid(request.orderId());
                    if (!"PAID".equalsIgnoreCase(updated.status())) throw new OrderServiceException("Order Service did not confirm the PAID status");
                    states.assertOwner(ticket);
                    inventoryClient.confirmWithRetry(updated.inventoryReservationId()!=null ? updated.inventoryReservationId() : order.inventoryReservationId(),inventoryConfirmAttempts,inventoryConfirmBackoffMs);
                    order=updated;
                } catch (OrderServiceException | InventoryServiceException error) {
                    states.assertOwner(ticket);
                    // A dependency failure after authorization is neither a
                    // decline nor a refund. Retain the reference for safe retry.
                    throw new PaymentProcessingException("Payment could not be completed",error);
                }
            } else {
                states.assertOwner(ticket);
                try { inventoryClient.confirmWithRetry(order.inventoryReservationId(),inventoryConfirmAttempts,inventoryConfirmBackoffMs); }
                catch (InventoryServiceException error) { log.error("Could not reconcile inventory for paid order {}",order.id(),error); }
            }
            payment.markSucceeded(reference);
            states.assertOwner(ticket);
            var response=PaymentResponse.from(payment);
            var tasks=followUps.plan(request,order,event(response,request.notificationRecipient(),order,null));
            var completed=states.finish(ticket,null,tasks);
            // The result and every required task have committed before any optional effect runs.
            followUpRunner.runOrder(request.orderId());
            return PaymentResponse.from(completed);
        } finally { states.release(ticket); }
    }

    private String resolveIdempotencyKey(CreatePaymentRequest request, String headerIdempotencyKey) {
        String body=request.idempotencyKey()==null ? null : request.idempotencyKey().trim();
        String header=headerIdempotencyKey==null ? null : headerIdempotencyKey.trim();
        if (body!=null && (body.length()>100 || body.chars().anyMatch(Character::isISOControl))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid idempotency key");
        if (header!=null && (header.isEmpty() || header.length()>100 || header.chars().anyMatch(Character::isISOControl))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid Idempotency-Key header");
        if (body!=null && !body.isEmpty() && header!=null && !body.equals(header)) throw new PaymentIdempotencyConflictException(request.orderId());
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return request.idempotencyKey().trim();
        }
        if (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank()) {
            return headerIdempotencyKey.trim();
        }
        return "order-" + request.orderId();
    }

    private void compensateOrder(OrderResponse order) {
        try {
            orderClient.cancel(order.id());
        } catch (OrderServiceException cancellationException) {
            log.warn("Declined order {} cancellation is awaiting recovery",order.id());
        }
    }

    private PaymentOutboxEvent event(PaymentResponse response,String recipient,OrderResponse order,Long invoiceId) {
        if (order.customerEmail()!=null) recipient=order.customerEmail();
        if (recipient==null || recipient.isBlank()) return null;
        String type=response.status()==PaymentStatus.SUCCEEDED ? "PAYMENT_SUCCEEDED" : response.status()==PaymentStatus.PENDING ? "PAYMENT_PENDING" : "PAYMENT_FAILED";
        return new PaymentOutboxEvent(type,response.orderId(),response.amount(),recipient,formatDeliveryAddress(order),CorrelationIdContext.currentOrNew(),invoiceId);
    }

    private String formatDeliveryAddress(OrderResponse order) {
        String scheduledDate = order.deliveryDate() == null ? null : order.deliveryDate().toString();
        String schedule = scheduledDate == null ? "" : ", termin: " + scheduledDate;
        if ("STORE_PICKUP".equalsIgnoreCase(order.fulfillmentType())) {
            String location = order.pickupLocation() == null || order.pickupLocation().isBlank()
                    ? "MiniTech Store — Warszawa" : order.pickupLocation();
            return "Punkt odbioru: " + location + schedule;
        }
        if (order.deliveryAddress() == null) {
            return scheduledDate == null ? null : "Termin dostawy: " + scheduledDate;
        }
        var address = order.deliveryAddress();
        return "%s %s, %s, %s %s, %s%s".formatted(
                address.firstName(), address.lastName(), address.street(),
                address.postalCode(), address.city(), address.country(), schedule
        );
    }

    public PaymentResponse get(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    public List<PaymentResponse> getAll() {
        return list(null, org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<PaymentResponse> list(PaymentStatus status, org.demo.platformsecurity.ListWindow window) {
        return window.map(paymentRepository.page(window.ceiling(), status, window.fetch()), Payment::getId, PaymentResponse::from);
    }

    public PaymentResponse getByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
    }
}
