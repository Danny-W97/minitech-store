package org.demo.orderservice.web;

import org.demo.orderservice.order.*;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.ListWindow;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderReconciliationController {
    private final OrderReconciliationService reconciliation;
    public OrderReconciliationController(OrderReconciliationService reconciliation) { this.reconciliation=reconciliation; }
    @GetMapping("/sagas")
    public ResponseEntity<List<OrderSagaResponse>> list(@RequestParam(defaultValue="RECONCILIATION_REQUIRED") OrderSagaState state,
            @RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="0") long cursor) {
        Access.require(Access.actor().operator());
        return reconciliation.list(state,new ListWindow(cursor,size)).response();
    }
    @PostMapping("/{id}/reconcile")
    public OrderResponse reconcile(@PathVariable Long id) {
        Access.require(Access.actor().operator());return reconciliation.reconcile(id);
    }
}
