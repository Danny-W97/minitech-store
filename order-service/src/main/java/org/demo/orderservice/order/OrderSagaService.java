package org.demo.orderservice.order;

import org.demo.orderservice.inventory.InventoryClient;
import org.demo.orderservice.inventory.InventoryConflictException;
import org.demo.orderservice.web.OrderSagaResponse;
import org.demo.platformsecurity.RequestBudget;
import org.demo.platformsecurity.BoundedJobs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;
import java.util.EnumSet;

@Service
public class OrderSagaService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderSagaService.class);
    private final OrderSagaRepository sagaRepository;
    private final OrderSagaStateStore states;
    private final InventoryClient inventory;
    @org.springframework.beans.factory.annotation.Autowired private org.demo.orderservice.payment.PaymentCancellationClient payment;
    @org.springframework.beans.factory.annotation.Autowired private org.demo.orderservice.promotion.PromotionClient promotions;
    private final BoundedJobs recovery=new BoundedJobs("saga-recovery",4,100,5000);
    public OrderSagaService(OrderSagaRepository sagaRepository,OrderSagaStateStore states,InventoryClient inventory) {
        this.sagaRepository=sagaRepository; this.states=states; this.inventory=inventory;
    }
    @jakarta.annotation.PreDestroy void closeJobs() { recovery.close(); }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void resume(Long id) { execute(id,false); }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void compensate(Long id) { execute(id,true); }

    private void execute(Long id,boolean cancel) {
        try (var ignored=RequestBudget.open(5000)) {
            var ticket=states.claim(id,cancel);
            if (ticket==null) return;
            try {
                RequestBudget.check(); Long reservation=ticket.reservationId();
                if (ticket.state()==OrderSagaState.INVENTORY_RESERVATION_PENDING) {
                    if(ticket.couponCode()!=null) promotions.reserve(ticket.couponCode(),id,ticket.subtotal(),ticket.discount(),ticket.acceptedAt(),ticket.couponExpiresAt());
                    RequestBudget.check();
                    var confirmed=inventory.reserve(id,ticket.items());
                    if(confirmed==null || !id.equals(confirmed.orderId()) || confirmed.reservationId()==null || confirmed.reservationId()<=0
                            || !"RESERVED".equals(confirmed.status()))
                        throw new org.demo.orderservice.inventory.InventoryServiceException("Inventory did not confirm this order's reservation");
                    reservation=confirmed.reservationId();
                }
                else {
                    payment.fence(id);
                    if(ticket.couponCode()!=null) promotions.release(ticket.couponCode(),id);
                    if(reservation==null) reservation=inventory.reservationForCancellation(id);
                    if(reservation!=null) inventory.releaseForCancellation(id,reservation);
                }
                if (states.complete(ticket,reservation)) execute(id,false);
            } catch (org.demo.orderservice.payment.CancellationRefusedException refused) {
                states.cancellationRefused(ticket);throw refused;
            } catch (RuntimeException failure) { states.failed(ticket,failure,failure instanceof InventoryConflictException || failure instanceof org.demo.orderservice.promotion.CouponReservationConflictException); throw failure; }
        }
    }
    public void resumeDueSagas() {
        resumeDueSagas(Instant.now());
    }

    void resumeDueSagas(Instant cutoff) {
        sagaRepository.due(EnumSet.of(OrderSagaState.INVENTORY_RESERVATION_PENDING,OrderSagaState.COMPENSATION_PENDING),cutoff,
                org.springframework.data.domain.PageRequest.of(0,100)).forEach(id -> recovery.submit(id,() -> {
                    try { resume(id); } catch (RuntimeException failure) { log.warn("Saga {} retry failed",id,failure); }
                }));
    }
    @Transactional(readOnly=true)
    public OrderSagaResponse getStatus(Long id) { return sagaRepository.findByOrderId(id).map(this::toResponse).orElseThrow(() -> new OrderNotFoundException(id)); }
    public void markPaymentConfirmed(Long id) { states.paid(id); }
    @Transactional(readOnly=true)
    public boolean requiresReconciliation(Long id) {
        return sagaRepository.findByOrderId(id).map(saga -> saga.getState()==OrderSagaState.RECONCILIATION_REQUIRED).orElse(true);
    }

    private OrderSagaResponse toResponse(OrderSaga saga) {
        return new OrderSagaResponse(
                saga.getOrderId(),
                saga.getState(),
                saga.getAttemptCount(),
                saga.getNextAttemptAt(),
                saga.getLastError(),
                saga.getCreatedAt(),
                saga.getUpdatedAt()
        );
    }
}
