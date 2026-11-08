package org.demo.orderservice.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.demo.orderservice.web.OrderResponse;
import java.math.BigDecimal;

/** Financial holds persist across restarts and block all fulfillment transitions. */
@Service
public class OrderRefundService {
    public record Command(Long paymentId,BigDecimal amount,String currency,boolean cancelCompensated,boolean external) {
        public Command(Long paymentId,BigDecimal amount,String currency,boolean cancelCompensated) { this(paymentId,amount,currency,cancelCompensated,false); }
    }
    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    public OrderRefundService(OrderRepository orders,OrderSagaRepository sagas) { this.orders=orders;this.sagas=sagas; }
    @Transactional
    public OrderResponse begin(Long id,Command command) {
        var saga=sagas.findByOrderIdForUpdate(id);
        var order=orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id));
        validate(order,command);
        if(order.getRefundPaymentId()!=null) {
            if(!order.getRefundPaymentId().equals(command.paymentId())) throw conflict();
            return OrderService.toResponse(order);
        }
        if(order.isLifecycleReviewRequired() && !command.external() || order.getDeferredPaymentMethod()!=null
                || !(order.getStatus()==OrderStatus.CONFIRMED || order.getStatus()==OrderStatus.PAID || order.getStatus()==OrderStatus.CANCELLED
                    || command.external() && (order.getStatus()==OrderStatus.SHIPPED || order.getStatus()==OrderStatus.COMPLETED))
                || saga.isEmpty() || saga.get().processing()
                || !(saga.get().getState()==OrderSagaState.WAITING_FOR_PAYMENT || saga.get().getState()==OrderSagaState.COMPLETED
                    || order.getStatus()==OrderStatus.CANCELLED && saga.get().getState()==OrderSagaState.FAILED))
            throw conflict();
        order.beginRefund(command.paymentId());orders.flush();return OrderService.toResponse(order);
    }
    @Transactional
    public OrderResponse complete(Long id,Command command) {
        var saga=sagas.findByOrderIdForUpdate(id);
        var order=orders.lockId(id).orElseThrow(() -> new OrderNotFoundException(id));
        validate(order,command);
        if(!command.paymentId().equals(order.getRefundPaymentId())) throw conflict();
        order.completeRefund(command.cancelCompensated());
        if(command.cancelCompensated()) saga.ifPresent(value -> { value.failed("Stripe refund and stock compensation confirmed");sagas.save(value); });
        orders.flush();return OrderService.toResponse(order);
    }
    private void validate(Order order,Command command) {
        boolean validAmount=command.amount()!=null && (command.external()
                ? command.amount().compareTo(BigDecimal.ZERO)>0 && command.amount().compareTo(order.getTotalPrice())<=0
                : order.getTotalPrice().compareTo(command.amount())==0);
        if(command.paymentId()==null || command.paymentId()<1 || !validAmount || !"PLN".equals(command.currency())) throw conflict();
    }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Order is not eligible for this Stripe refund or requires reconciliation"); }
}
