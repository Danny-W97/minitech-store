package org.demo.orderservice.order;

import org.demo.orderservice.inventory.InventoryReservationItemRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderSagaStateStore {
    public record Ticket(Long orderId,String token,OrderSagaState state,Long reservationId,List<InventoryReservationItemRequest> items,
            String couponCode,java.math.BigDecimal subtotal,java.math.BigDecimal discount,Instant acceptedAt,Instant couponExpiresAt) {}
    private final OrderSagaRepository sagas;
    private final OrderRepository orders;
    private final long initialBackoff,maxBackoff,leaseMillis;
    public OrderSagaStateStore(OrderSagaRepository sagas,OrderRepository orders,
            @Value("${order.saga.retry.initial-backoff-ms:1000}") long initial,
            @Value("${order.saga.retry.max-backoff-ms:60000}") long maximum,
            @Value("${order.saga.processing-lease-ms:120000}") long lease) {
        if (initial<1 || maximum<initial || maximum>3600000 || lease<60000) throw new IllegalArgumentException("Invalid saga retry/lease settings");
        this.sagas=sagas; this.orders=orders; initialBackoff=initial; maxBackoff=maximum; leaseMillis=lease;
    }
    @Transactional
    public Ticket claim(Long id,boolean cancel) {
        var saga=sagas.findByOrderIdForUpdate(id).orElse(null);
        if(cancel) {
            var order=orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id));
            if(order.getStatus()==OrderStatus.CANCELLED) return null;
            requireUnfulfilled(order);
            if(saga==null) {
                if(sagas.findByOrderId(id).isPresent())
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Order lifecycle changed; retry cancellation");
                saga=sagas.saveAndFlush(new OrderSaga(id));
            }
        }
        if(saga==null) return null;
        if (saga.processing()) {
            if (cancel) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Order processing is in progress; retry cancellation later");
            return null;
        }
        if (cancel) saga.compensationPending(Instant.now(),"Order cancellation requested");
        if (saga.getState()!=OrderSagaState.INVENTORY_RESERVATION_PENDING && saga.getState()!=OrderSagaState.COMPENSATION_PENDING) return null;
        var order=orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id));
        if(saga.getState()==OrderSagaState.INVENTORY_RESERVATION_PENDING
                && (order.getStatus()!=OrderStatus.CREATED || order.getDeferredPaymentMethod()!=null || order.isLifecycleReviewRequired())) {
            order.requireLifecycleReview();saga.reconciliationRequired("Reservation recovery conflicts with the stored order lifecycle");
            orders.save(order);sagas.save(saga);return null;
        }
        String token=UUID.randomUUID().toString(); saga.claim(token,Instant.now().plusMillis(leaseMillis)); sagas.save(saga);
        return new Ticket(id,token,saga.getState(),order.getInventoryReservationId(),order.getItems().stream()
                .map(item -> new InventoryReservationItemRequest(item.getProductId(),item.getQuantity())).toList(),
                order.getPromotionCode(),order.getTotalPrice().add(order.getDiscountAmount()).subtract(order.getDeliveryCost()),order.getDiscountAmount(),order.getCreatedAt(),order.getCouponExpiresAt());
    }
    @Transactional
    public boolean complete(Ticket ticket,Long reservationId) {
        var saga=sagas.findByOrderIdForUpdate(ticket.orderId()).orElseThrow();
        if (!owns(saga,ticket)) return false;
        var order=orders.lockId(ticket.orderId()).orElseThrow();
        if (ticket.state()==OrderSagaState.INVENTORY_RESERVATION_PENDING) {
            if(order.getStatus()!=OrderStatus.CREATED || order.getDeferredPaymentMethod()!=null || order.isLifecycleReviewRequired()) {
                order.requireLifecycleReview();saga.reconciliationRequired("Late reservation result requires lifecycle reconciliation");
            } else {
                if(reservationId==null || reservationId<=0) throw new IllegalArgumentException("Valid reservation ID required");
                order.assignInventoryReservation(reservationId);order.changeStatus(OrderStatus.CONFIRMED);saga.waitingForPayment();
                if(ticket.couponCode()!=null) order.verifyCouponReservation();
            }
        } else {
            requireUnfulfilled(order);
            order.changeStatus(OrderStatus.CANCELLED); saga.failed("Order cancelled after payment fence and inventory release");
        }
        orders.save(order); saga.release(); sagas.save(saga);
        return saga.getState()==OrderSagaState.COMPENSATION_PENDING;
    }
    @Transactional
    public void failed(Ticket ticket,RuntimeException failure,boolean conflict) {
        var saga=sagas.findByOrderIdForUpdate(ticket.orderId()).orElseThrow();
        if (!owns(saga,ticket)) return;
        String message=failure.getMessage()==null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length()>2000) message=message.substring(0,2000);
        if (conflict && ticket.state()==OrderSagaState.INVENTORY_RESERVATION_PENDING) {
            var order=orders.lockId(ticket.orderId()).orElseThrow();
            // A historical or manually advanced order may have reached payment
            // while this reservation attempt was outstanding. Never erase it.
            if(order.getStatus()==OrderStatus.CREATED && order.getDeferredPaymentMethod()==null) {
                if(order.getPromotionCode()!=null) saga.compensationPending(Instant.now(),message);
                else { order.changeStatus(OrderStatus.CANCELLED);orders.save(order);saga.failed(message); }
            } else if(order.getStatus()==OrderStatus.CANCELLED) saga.failed(message);
            else { order.requireLifecycleReview();orders.save(order);saga.reconciliationRequired("Late reservation failure requires lifecycle reconciliation"); }
        } else if(conflict && ticket.state()==OrderSagaState.COMPENSATION_PENDING && failure instanceof org.demo.orderservice.promotion.CouponReservationConflictException) {
            orders.lockId(ticket.orderId()).orElseThrow().requireLifecycleReview();saga.reconciliationRequired("Coupon release requires reconciliation");
        } else {
            long exponent=Math.min(Math.max(0,saga.getAttemptCount()-1),10);
            saga.recordAttempt(Instant.now().plusMillis(Math.min(maxBackoff,initialBackoff*(1L<<exponent))),message);
        }
        saga.release(); sagas.save(saga);
    }
    @Transactional
    public void paid(Long id) {
        sagas.findByOrderIdForUpdate(id).ifPresent(saga -> {
            if (saga.getState()==OrderSagaState.WAITING_FOR_PAYMENT) { saga.completed(); sagas.save(saga); }
        });
    }
    @Transactional
    public void cancellationRefused(Ticket ticket) {
        var saga=sagas.findByOrderIdForUpdate(ticket.orderId()).orElseThrow();
        if(!owns(saga,ticket)) return;
        var order=orders.lockId(ticket.orderId()).orElseThrow();
        if(order.getStatus()==OrderStatus.CREATED) saga.restartReservation();
        else if("PAID".equals(order.getPaymentState())) saga.completed();
        else saga.waitingForPayment();
        saga.release();sagas.save(saga);
    }
    private void requireUnfulfilled(Order order) {
        if(order.isLifecycleReviewRequired() || order.getDeferredPaymentMethod()!=null || !(order.getStatus()==OrderStatus.CREATED || order.getStatus()==OrderStatus.CONFIRMED))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Paid or accepted fulfillment requires an explicit refund/compensation process");
    }
    private boolean owns(OrderSaga saga,Ticket ticket) {
        return ticket.token().equals(saga.getProcessingToken()) && saga.processing() && saga.getState()==ticket.state();
    }
    private Order order(Long id) { return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id)); }
}
