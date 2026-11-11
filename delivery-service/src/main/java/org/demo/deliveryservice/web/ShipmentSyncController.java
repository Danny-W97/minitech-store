package org.demo.deliveryservice.web;

import org.demo.deliveryservice.delivery.*;
import org.demo.platformsecurity.Access;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShipmentSyncController {
    private final ShipmentSyncService sync;
    private final ShipmentSyncStateStore states;
    public ShipmentSyncController(ShipmentSyncService sync,ShipmentSyncStateStore states) { this.sync=sync;this.states=states; }
    @GetMapping("/deliveries/orders/{id}/synchronization") public ShipmentSyncStateStore.Response status(@PathVariable Long id) {
        Access.require(Access.actor().operator());return states.status(id);
    }
    @PostMapping("/deliveries/orders/{id}/synchronization/reconcile") public ShipmentSyncStateStore.Response reconcile(@PathVariable Long id) {
        Access.require(Access.actor().operator());sync.reconcile(id);return states.status(id);
    }
}
