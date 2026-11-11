package org.demo.orderservice.order;

import org.demo.orderservice.inventory.InventoryClient;
import org.demo.platformsecurity.ListWindow;
import org.demo.orderservice.web.OrderSagaResponse;
import org.demo.orderservice.web.OrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.stream.Collectors;

@Service
public class OrderReconciliationService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderReconciliationService.class);
    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    private final OrderReconciliationStore states;
    private final InventoryClient inventory;
    private final int batchSize;
    private long cursor;
    public OrderReconciliationService(OrderRepository orders,OrderSagaRepository sagas,OrderReconciliationStore states,InventoryClient inventory,
            @Value("${order.saga.reconciliation-batch-size:100}") int batchSize) {
        if(batchSize<1 || batchSize>500) throw new IllegalArgumentException("Reconciliation batch size must be 1..500");
        this.orders=orders;this.sagas=sagas;this.states=states;this.inventory=inventory;this.batchSize=batchSize;
    }
    /** Bounded keyset scan, including pages without orphans. */
    public synchronized void scanMissing() {
        var ids=orders.scanIdsAfter(cursor,PageRequest.of(0,batchSize));
        if(ids.isEmpty()) { cursor=0;return; }
        for(Long id:orders.missingSagaIds(ids)) {
            try { states.attachMissing(id); }
            catch(RuntimeException failure) { log.warn("Order {} reconciliation scan deferred",id); }
        }
        cursor=ids.getLast();
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public OrderResponse reconcile(Long id) {
        try(var budget=org.demo.platformsecurity.RequestBudget.open(5000)) {
            states.attachMissing(id);
            var ticket=states.claim(id);
            if(ticket==null) return response(id);
            try {
                var row=inventory.lookupForReconciliation(id);
                var expected=ticket.items().stream().collect(Collectors.toMap(item -> item.productId(),item -> item.quantity()));
                var actual=row==null || row.items()==null ? java.util.Map.<Long,Integer>of() : row.items().stream()
                        .collect(Collectors.toMap(item -> item.productId(),item -> item.quantity(),(a,b) -> { throw conflict("Duplicate inventory product"); }));
                if(row==null || !id.equals(row.orderId()) || row.reservationId()==null || row.reservationId()<=0
                        || ticket.reservationId()!=null && !ticket.reservationId().equals(row.reservationId())
                        || !"RESERVED".equals(row.status()) || row.expiresAt()==null || !row.expiresAt().isAfter(Instant.now())
                        || expected.isEmpty() || !expected.equals(actual))
                    throw conflict("Reservation identity, items, status or expiry do not prove safe unpaid fulfillment");
                states.reconciled(ticket,row.reservationId());
            } finally { states.release(ticket); }
            // EntityGraph loads items before this detached response is mapped.
            return response(id);
        }
    }
    private OrderResponse response(Long id) {
        return orders.findById(id).map(OrderService::toResponse).orElseThrow(() -> new OrderNotFoundException(id));
    }
    @Transactional(readOnly=true)
    public ListWindow.ListPage<OrderSagaResponse> list(OrderSagaState state,ListWindow window) {
        return window.map(sagas.page(window.ceiling(),state,window.fetch()),OrderSaga::getId,
                saga -> new OrderSagaResponse(saga.getOrderId(),saga.getState(),saga.getAttemptCount(),saga.getNextAttemptAt(),
                        saga.getLastError(),saga.getCreatedAt(),saga.getUpdatedAt()));
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
