package org.demo.orderservice.order;

import org.demo.orderservice.inventory.InventoryReservationItemRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Each mutation uses the same saga -> order lock order as checkout/cancellation. */
@Service
public class OrderReconciliationStore {
    public record Assessment(Long orderId,String token,long version,Long reservationId,List<InventoryReservationItemRequest> items) {}
    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    public OrderReconciliationStore(OrderRepository orders,OrderSagaRepository sagas) { this.orders=orders;this.sagas=sagas; }
    @Transactional
    public void attachMissing(Long id) {
        if(sagas.findByOrderIdForUpdate(id).isPresent()) return;
        var order=orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id));
        // Another replica can have created the row while we waited for the order.
        // Do not lock that row after the order: a normal writer locks saga first.
        if(sagas.findByOrderId(id).isPresent()) return;
        var saga=new OrderSaga(id);
        if(order.getStatus()!=OrderStatus.CREATED || order.getDeferredPaymentMethod()!=null || order.isLifecycleReviewRequired()
                || !safeSnapshot(order)) {
            order.requireLifecycleReview();
            saga.reconciliationRequired("Historical order without saga; verify inventory and financial history before resuming");
        }
        sagas.saveAndFlush(saga);orders.flush();
    }
    @Transactional
    public Assessment claim(Long id) {
        var saga=sagas.findByOrderIdForUpdate(id).orElseThrow(() -> conflict("Reconciliation scan has not attached a saga"));
        var order=orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id));
        if(order.getRefundPaymentId()!=null) throw conflict("Stripe refund hold requires financial and business reconciliation");
        if(!order.isLifecycleReviewRequired() && order.getStatus()==OrderStatus.CONFIRMED
                && order.getInventoryReservationId()!=null && saga.getState()==OrderSagaState.WAITING_FOR_PAYMENT) return null;
        if(!order.isLifecycleReviewRequired() || saga.getState()!=OrderSagaState.RECONCILIATION_REQUIRED
                || order.getStatus()!=OrderStatus.CONFIRMED || order.getDeferredPaymentMethod()!=null || !safeSnapshot(order))
            throw conflict("Only historical unpaid CONFIRMED orders can be reconciled from inventory; other history requires financial verification");
        if(saga.processing()) throw conflict("Order reconciliation is already in progress");
        String token=UUID.randomUUID().toString();saga.claim(token,Instant.now().plusSeconds(120));sagas.flush();
        return new Assessment(id,token,order.getVersion(),order.getInventoryReservationId(),order.getItems().stream()
                .map(item -> new InventoryReservationItemRequest(item.getProductId(),item.getQuantity())).toList());
    }
    @Transactional
    public void reconciled(Assessment ticket,Long reservationId) {
        var saga=sagas.findByOrderIdForUpdate(ticket.orderId()).orElseThrow();
        var order=orders.lockId(ticket.orderId()).orElseThrow();
        if(!ticket.token().equals(saga.getProcessingToken()) || !saga.processing() || saga.getState()!=OrderSagaState.RECONCILIATION_REQUIRED
                || order.getVersion()!=ticket.version() || order.getStatus()!=OrderStatus.CONFIRMED
                || !order.isLifecycleReviewRequired() || order.getDeferredPaymentMethod()!=null)
            throw conflict("Order changed while its reservation was being verified");
        order.assignInventoryReservation(reservationId);order.clearLifecycleReview();saga.waitingForPayment();saga.release();
        orders.flush();sagas.flush();
    }
    @Transactional
    public void release(Assessment ticket) {
        var saga=sagas.findByOrderIdForUpdate(ticket.orderId()).orElseThrow();
        if(ticket.token().equals(saga.getProcessingToken())) { saga.release();sagas.flush(); }
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private boolean safeSnapshot(Order order) {
        if(order.getItems().isEmpty()) return false;
        var ids=new java.util.HashSet<Long>();var subtotal=java.math.BigDecimal.ZERO;
        for(var item:order.getItems()) {
            if(item.getProductId()==null || item.getProductId()<=0 || !ids.add(item.getProductId()) || item.getQuantity()==null
                    || item.getQuantity()<1 || item.getUnitPrice()==null || item.getUnitPrice().signum()<0 || item.getTotalPrice()==null
                    || item.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())).compareTo(item.getTotalPrice())!=0) return false;
            subtotal=subtotal.add(item.getTotalPrice());
        }
        return order.getDeliveryCost()!=null && order.getDeliveryCost().signum()>=0 && order.getDiscountAmount()!=null
                && order.getDiscountAmount().signum()>=0 && order.getTotalPrice()!=null
                && subtotal.add(order.getDeliveryCost()).subtract(order.getDiscountAmount()).max(java.math.BigDecimal.ZERO).compareTo(order.getTotalPrice())==0;
    }
}
