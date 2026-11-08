package org.demo.orderservice.order;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** A delivery fact advances fulfillment only; it never records money or removes a hold. */
@Service
public class OrderShipmentService {
    public record Command(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{4,60}") String trackingNumber,
            @NotBlank @Size(max=40) String methodCode,@NotNull @PositiveOrZero Long revision,
            @NotBlank @Pattern(regexp="LABEL_CREATED|READY_FOR_PICKUP|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|PICKED_UP|CANCELLED") String status) {}
    public record Result(Long orderId,String trackingNumber,long revision,String deliveryStatus,String orderStatus,String paymentState,String outcome) {}
    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    public OrderShipmentService(OrderRepository orders,OrderSagaRepository sagas) { this.orders=orders;this.sagas=sagas; }
    @Transactional public Result apply(Long id,Command command) {
        var saga=sagas.findByOrderIdForUpdate(id);
        var order=orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id));
        if(!order.getDeliveryMethod().equals(command.methodCode()) || order.getDeliveryTrackingNumber()!=null
                && !order.getDeliveryTrackingNumber().equals(command.trackingNumber())) throw conflict("Shipment identity does not match order");
        if(command.revision()<=order.getDeliveryRevision()) {
            if(command.revision()==order.getDeliveryRevision() && !command.status().equals(order.getDeliveryStatus())) throw conflict("Shipment revision conflicts with recorded status");
            return result(order,"ALREADY_APPLIED");
        }
        if(order.isLifecycleReviewRequired() || order.getRefundPaymentId()!=null || order.getStatus()==OrderStatus.CANCELLED)
            throw conflict("Financial hold or order reconciliation blocks shipment progress");
        if(saga.isEmpty() || !Set.of(OrderSagaState.WAITING_FOR_PAYMENT,OrderSagaState.COMPLETED).contains(saga.get().getState()))
            throw conflict("Order lifecycle requires reconciliation");
        if(saga.get().processing() || order.getInventoryReservationId()==null)
            throw new ResponseStatusException(HttpStatus.TOO_EARLY,"Order fulfillment is not ready");
        boolean deferred=order.getDeferredPaymentMethod()!=null;
        var expectedSaga=deferred && order.getCollectionReference()==null ? OrderSagaState.WAITING_FOR_PAYMENT : OrderSagaState.COMPLETED;
        if(saga.get().getState()!=expectedSaga && (deferred || order.getStatus()!=OrderStatus.CONFIRMED)) throw conflict("Order and saga lifecycle disagree");
        if(!deferred && !Set.of(OrderStatus.PAID,OrderStatus.SHIPPED,OrderStatus.COMPLETED).contains(order.getStatus()))
            throw new ResponseStatusException(HttpStatus.TOO_EARLY,"Order payment is not confirmed");
        if(deferred && !Set.of(OrderStatus.CONFIRMED,OrderStatus.SHIPPED,OrderStatus.COMPLETED).contains(order.getStatus())) throw conflict("Invalid deferred lifecycle");
        boolean pickup="STORE_PICKUP".equals(command.methodCode());
        if("CANCELLED".equals(command.status()) || pickup && !Set.of("READY_FOR_PICKUP","PICKED_UP").contains(command.status())
                || !pickup && Set.of("READY_FOR_PICKUP","PICKED_UP").contains(command.status())) throw conflict("Shipment status requires manual reconciliation");
        if("DELIVERED".equals(command.status()) || "PICKED_UP".equals(command.status())) order.changeStatus(OrderStatus.COMPLETED);
        else if(Set.of("IN_TRANSIT","OUT_FOR_DELIVERY").contains(command.status()) && order.getStatus()!=OrderStatus.COMPLETED) order.changeStatus(OrderStatus.SHIPPED);
        order.recordDelivery(command.trackingNumber(),command.revision(),command.status());orders.flush();
        return result(order,"APPLIED");
    }
    private Result result(Order order,String outcome) {
        return new Result(order.getId(),order.getDeliveryTrackingNumber(),order.getDeliveryRevision(),order.getDeliveryStatus(),
                order.getStatus().name(),order.getPaymentState(),outcome);
    }
    private ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT,reason); }
}
