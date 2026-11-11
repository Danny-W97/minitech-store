package org.demo.orderservice.order;

import org.demo.orderservice.web.OrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;

/** Short local transactions. The payment service verifies inventory and handover outside SQL. */
@Service
public class DeferredFulfillmentService {
    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    public DeferredFulfillmentService(OrderRepository orders,OrderSagaRepository sagas) { this.orders=orders;this.sagas=sagas; }
    @Transactional public OrderResponse accept(Long id,Long paymentId,String method,BigDecimal amount,String currency) {
        var saga=sagas.findByOrderIdForUpdate(id);var order=lock(id);validateMoney(order,amount,currency);
        requireReady(order,saga);
        if(saga.isPresent() && saga.get().getState()==OrderSagaState.COMPENSATION_PENDING) throw conflict("Cancellation is in progress");
        boolean pickup="STORE_PICKUP".equals(order.getDeliveryMethod());
        if(!(pickup && "CASH_AT_PICKUP".equals(method))
                && !("STANDARD_COURIER".equals(order.getDeliveryMethod()) && "CASH_ON_DELIVERY".equals(method))) throw conflict("Cash method does not match delivery");
        if(order.getDeferredPaymentMethod()!=null) {
            if(!method.equals(order.getDeferredPaymentMethod()) || !paymentId.equals(order.getDeferredPaymentId()) || order.getStatus()==OrderStatus.CANCELLED)
                throw conflict("Deferred payment does not match the accepted order");
            return OrderService.toResponse(order);
        }
        if(order.getStatus()!=OrderStatus.CONFIRMED || order.getInventoryReservationId()==null) throw conflict("Order is not ready for deferred fulfillment");
        order.acceptDeferred(paymentId,method);orders.flush();return OrderService.toResponse(order);
    }
    @Transactional public OrderResponse collect(Long id,Long paymentId,String reference,BigDecimal amount,String currency) {
        var saga=sagas.findByOrderIdForUpdate(id);var order=lock(id);validateMoney(order,amount,currency);
        requireReady(order,saga);
        if(order.getDeferredPaymentMethod()==null || !paymentId.equals(order.getDeferredPaymentId()) || order.getStatus()==OrderStatus.CANCELLED)
            throw conflict("Order has no matching deferred payment");
        if(order.getCollectionReference()!=null) {
            if(!reference.equals(order.getCollectionReference())) throw conflict("Collection reference conflicts with the recorded receipt");
            return OrderService.toResponse(order);
        }
        order.collectDeferred(reference);saga.ifPresent(value -> { value.completed();sagas.save(value); });
        orders.flush();return OrderService.toResponse(order);
    }
    @Transactional public OrderResponse transition(Long id,OrderStatus requested) {
        var saga=sagas.findByOrderIdForUpdate(id);var order=lock(id);var current=order.getStatus();
        requireReady(order,saga);
        if(saga.isPresent() && saga.get().getState()==OrderSagaState.COMPENSATION_PENDING && current!=requested)
            throw conflict("Cancellation is in progress; retry payment reconciliation after it resolves");
        if(requested==OrderStatus.PAID && order.getDeferredPaymentMethod()!=null) throw conflict("Record cash collection through Payment Service");
        boolean deferred=order.getDeferredPaymentMethod()!=null;
        boolean allowed=current==requested || switch(current) {
            case CREATED -> false;
            case CONFIRMED -> requested==OrderStatus.PAID || deferred && (requested==OrderStatus.SHIPPED
                    || "STORE_PICKUP".equals(order.getDeliveryMethod()) && requested==OrderStatus.COMPLETED);
            case PAID -> requested==OrderStatus.SHIPPED;
            case SHIPPED -> requested==OrderStatus.COMPLETED;
            default -> false;
        };
        if(!allowed) throw new InvalidOrderStatusTransitionException(current,requested);
        if(requested==OrderStatus.PAID && order.getInventoryReservationId()==null) throw conflict("Inventory reservation must be recorded before payment");
        order.changeStatus(requested);
        if(requested==OrderStatus.PAID) saga.ifPresent(value -> { value.completed();sagas.save(value); });
        orders.flush();return OrderService.toResponse(order);
    }
    private Order lock(Long id) { return orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id)); }
    private void requireReady(Order order,java.util.Optional<OrderSaga> saga) {
        if(order.isLifecycleReviewRequired() || saga.isEmpty() || saga.get().getState()==OrderSagaState.RECONCILIATION_REQUIRED)
            throw conflict("Order lifecycle requires reconciliation");
        if(!(saga.get().getState()==OrderSagaState.WAITING_FOR_PAYMENT || saga.get().getState()==OrderSagaState.COMPLETED) || saga.get().processing())
            throw conflict("Inventory or lifecycle processing is still in progress");
        if(order.getStatus()==OrderStatus.CONFIRMED && saga.get().getState()!=OrderSagaState.WAITING_FOR_PAYMENT)
            throw conflict("Order and saga lifecycle disagree");
    }
    private void validateMoney(Order order,BigDecimal amount,String currency) {
        if(amount==null || amount.compareTo(order.getTotalPrice())!=0 || !"PLN".equals(currency)) throw conflict("Collection amount or currency does not match order");
    }
    private ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT,reason); }
}
