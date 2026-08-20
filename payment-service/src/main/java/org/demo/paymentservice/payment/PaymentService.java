package org.demo.paymentservice.payment;

import org.demo.paymentservice.inventory.InventoryClient;
import org.demo.paymentservice.inventory.InventoryServiceException;
import org.demo.paymentservice.delivery.DeliveryClient;
import org.demo.paymentservice.delivery.DeliveryServiceException;
import org.demo.paymentservice.billing.BillingClient;
import org.demo.paymentservice.billing.BillingServiceException;
import org.demo.paymentservice.order.OrderClient;
import org.demo.paymentservice.order.OrderResponse;
import org.demo.paymentservice.order.OrderServiceException;
import org.demo.paymentservice.outbox.PaymentOutboxEvent;
import org.demo.paymentservice.outbox.PaymentOutboxRepository;
import org.demo.paymentservice.observability.CorrelationIdContext;
import org.demo.paymentservice.promotion.PromotionClient;
import org.demo.paymentservice.promotion.PromotionServiceException;
import org.demo.paymentservice.web.CreatePaymentRequest;
import org.demo.paymentservice.web.PaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final OrderClient orderClient;
    private final InventoryClient inventoryClient;
    private final PaymentOutboxRepository outboxRepository;
    private final PromotionClient promotionClient;
    private final DeliveryClient deliveryClient;
    private final BillingClient billingClient;

    @Value("${payment.inventory-confirm-attempts:3}")
    private int inventoryConfirmAttempts = 3;

    @Value("${payment.inventory-confirm-backoff-ms:250}")
    private long inventoryConfirmBackoffMs = 250;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderClient orderClient,
            InventoryClient inventoryClient,
            PaymentOutboxRepository outboxRepository,
            PromotionClient promotionClient,
            DeliveryClient deliveryClient,
            BillingClient billingClient
    ) {
        this.paymentRepository = paymentRepository;
        this.orderClient = orderClient;
        this.inventoryClient = inventoryClient;
        this.outboxRepository = outboxRepository;
        this.promotionClient = promotionClient;
        this.deliveryClient = deliveryClient;
        this.billingClient = billingClient;
    }

    @Transactional(noRollbackFor = PaymentProcessingException.class)
    public PaymentResponse create(CreatePaymentRequest request) {
        return create(request, null);
    }

    @Transactional(noRollbackFor = PaymentProcessingException.class)
    public PaymentResponse create(CreatePaymentRequest request, String headerIdempotencyKey) {
        log.info("Processing payment for order {}", request.orderId());
        String idempotencyKey = resolveIdempotencyKey(request, headerIdempotencyKey);
        Payment existingPayment = paymentRepository.findByOrderId(request.orderId()).orElse(null);
        Payment paymentByKey = paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (paymentByKey != null && !paymentByKey.getOrderId().equals(request.orderId())) {
            throw new PaymentIdempotencyConflictException(request.orderId());
        }
        if (existingPayment == null && paymentByKey != null) {
            existingPayment = paymentByKey;
        }
        if (existingPayment != null && existingPayment.getStatus() == PaymentStatus.SUCCEEDED) {
            // A repeated payment request is intentionally side-effect free. The
            // first successful transaction already confirmed inventory, issued
            // the invoice and queued the notification; retrying must not redeem
            // a coupon or send a second e-mail.
            return PaymentResponse.from(existingPayment);
        }

        OrderResponse order = orderClient.getOrder(request.orderId());
        if ("PAID".equalsIgnoreCase(order.status())) {
            return reconcilePaidOrder(request, order, existingPayment, idempotencyKey);
        }
        if (!"CONFIRMED".equalsIgnoreCase(order.status())) {
            throw new OrderNotPayableException(request.orderId(), order.status());
        }

        var amount = order.totalPrice().setScale(2, RoundingMode.HALF_UP);
        String paymentMethod = request.paymentMethod().trim().toUpperCase();
        Payment payment = existingPayment;
        if (payment == null) {
            payment = new Payment(request.orderId(), amount, paymentMethod, idempotencyKey);
        } else {
            payment.prepareForRetry(amount, paymentMethod);
        }
        payment = paymentRepository.save(payment);

        if (isDeferredPayment(paymentMethod)) {
            PaymentResponse response = PaymentResponse.from(payment);
            enqueueNotification(response, request.notificationRecipient(), order);
            log.info("Payment for order {} is pending until order collection", request.orderId());
            return response;
        }

        if (request.simulateFailure()) {
            payment.markFailed("Payment declined by the simulation");
            PaymentResponse response = PaymentResponse.from(paymentRepository.save(payment));
            compensateOrder(order);
            enqueueNotification(response, request.notificationRecipient(), order);
            return response;
        }

        OrderResponse updatedOrder;
        try {
            updatedOrder = orderClient.markPaid(request.orderId());
            if (!"PAID".equalsIgnoreCase(updatedOrder.status())) {
                throw new OrderServiceException("Order Service did not confirm the PAID status");
            }
            inventoryClient.confirmWithRetry(updatedOrder.inventoryReservationId() != null
                    ? updatedOrder.inventoryReservationId()
                    : order.inventoryReservationId(),
                    inventoryConfirmAttempts,
                    inventoryConfirmBackoffMs);
        } catch (OrderServiceException | InventoryServiceException exception) {
            log.warn(
                    "Payment orchestration failed for order {}: {}. Starting compensation.",
                    request.orderId(),
                    exception.getMessage(),
                    exception
            );
            payment.markFailed("Order status could not be updated after payment authorization");
            PaymentResponse response = PaymentResponse.from(paymentRepository.save(payment));
            compensateOrder(order);
            enqueueNotification(response, request.notificationRecipient(), order);
            throw new PaymentProcessingException("Payment could not be completed", exception);
        }

        payment.markSucceeded("SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        PaymentResponse response = PaymentResponse.from(paymentRepository.save(payment));
        log.info("Payment {} succeeded for order {}", payment.getId(), request.orderId());
        completePaidOrder(request, updatedOrder, response);
        return response;
    }

    private PaymentResponse reconcilePaidOrder(CreatePaymentRequest request, OrderResponse order,
                                               Payment existingPayment, String idempotencyKey) {
        var amount = order.totalPrice().setScale(2, RoundingMode.HALF_UP);
        String paymentMethod = request.paymentMethod().trim().toUpperCase();
        Payment payment = existingPayment;
        if (payment == null) {
            payment = new Payment(request.orderId(), amount, paymentMethod, idempotencyKey);
        } else {
            payment.prepareForRetry(amount, paymentMethod);
        }
        payment.markSucceeded("RECOVERED-ORDER-" + request.orderId());
        PaymentResponse response = PaymentResponse.from(paymentRepository.save(payment));

        Long reservationId = order.inventoryReservationId();
        if (reservationId != null) {
            try {
                inventoryClient.confirmWithRetry(reservationId, inventoryConfirmAttempts, inventoryConfirmBackoffMs);
            } catch (InventoryServiceException exception) {
                log.error("Could not reconcile inventory for already paid order {}: {}",
                        order.id(), exception.getMessage(), exception);
            }
        }
        log.warn("Reconciled payment record for order {} that was already PAID", order.id());
        completePaidOrder(request, order, response);
        return response;
    }

    private void completePaidOrder(CreatePaymentRequest request, OrderResponse paidOrder, PaymentResponse response) {
        if (paidOrder.promotionCode() != null && !paidOrder.promotionCode().isBlank()) {
            try {
                promotionClient.redeemCoupon(paidOrder.promotionCode(), paidOrder.id());
            } catch (PromotionServiceException exception) {
                log.error("Could not redeem promotion code for paid order {}: {}",
                        paidOrder.id(), exception.getMessage(), exception);
            }
        }
        try {
            deliveryClient.ensureTracking(paidOrder.id(), paidOrder.deliveryMethod());
        } catch (DeliveryServiceException exception) {
            log.error("Could not create shipment tracking for paid order {}: {}",
                    paidOrder.id(), exception.getMessage(), exception);
        }
        Long invoiceId = null;
        if (Boolean.TRUE.equals(request.invoiceRequested()) && billingClient != null) {
            try {
                invoiceId = billingClient.issue(request, paidOrder);
            } catch (BillingServiceException exception) {
                log.error("Could not issue invoice for paid order {}: {}", paidOrder.id(), exception.getMessage(), exception);
            }
        }
        enqueueNotification(response, request.notificationRecipient(), paidOrder, invoiceId);
    }

    private String resolveIdempotencyKey(CreatePaymentRequest request, String headerIdempotencyKey) {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return request.idempotencyKey().trim();
        }
        if (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank()) {
            return headerIdempotencyKey.trim();
        }
        return "order-" + request.orderId();
    }

    private boolean isDeferredPayment(String paymentMethod) {
        return "CASH_ON_DELIVERY".equals(paymentMethod) || "CASH_AT_PICKUP".equals(paymentMethod);
    }

    private void compensateOrder(OrderResponse order) {
        try {
            orderClient.cancel(order.id());
        } catch (OrderServiceException cancellationException) {
            try {
                inventoryClient.release(order.inventoryReservationId());
            } catch (InventoryServiceException releaseException) {
                cancellationException.addSuppressed(releaseException);
                throw new PaymentProcessingException(
                        "Payment failed and inventory compensation could not be completed",
                        cancellationException
                );
            }
        }
    }

    private void enqueueNotification(PaymentResponse response, String recipient, OrderResponse order) {
        enqueueNotification(response, recipient, order, null);
    }

    private void enqueueNotification(PaymentResponse response, String recipient, OrderResponse order, Long invoiceId) {
        if (recipient == null || recipient.isBlank()) {
            return;
        }
        String eventType = response.status() == PaymentStatus.SUCCEEDED
                ? "PAYMENT_SUCCEEDED"
                : response.status() == PaymentStatus.PENDING
                ? "PAYMENT_PENDING"
                : "PAYMENT_FAILED";
        String eventKey = "%s:%d:%s".formatted(eventType, response.orderId(), recipient);
        if (outboxRepository.findByEventKey(eventKey).isEmpty()) {
            outboxRepository.save(new PaymentOutboxEvent(
                    eventType,
                    response.orderId(),
                    response.amount(),
                    recipient,
                    formatDeliveryAddress(order),
                    CorrelationIdContext.currentOrNew(),
                    invoiceId
            ));
            log.info("Queued payment notification event {} for order {}", eventType, response.orderId());
        }
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
        return paymentRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(PaymentResponse::from)
                .toList();
    }

    public PaymentResponse getByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
    }
}
