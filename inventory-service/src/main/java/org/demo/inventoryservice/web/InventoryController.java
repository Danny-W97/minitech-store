package org.demo.inventoryservice.web;

import jakarta.validation.Valid;
import org.demo.inventoryservice.inventory.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/items/{productId}")
    public InventoryItemResponse getItem(@PathVariable Long productId) {
        return inventoryService.getItem(productId);
    }

    @GetMapping("/items/batch")
    public List<InventoryItemResponse> getItems(@RequestParam List<Long> productIds) {
        return inventoryService.getItems(productIds);
    }

    @PostMapping("/items")
    public ResponseEntity<InventoryItemResponse> createItem(
            @Valid @RequestBody CreateInventoryItemRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createItem(request));
    }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> reserve(
            @Valid @RequestBody ReserveInventoryRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.reserve(request));
    }

    @PostMapping("/reservations/{reservationId}/release")
    public ReservationResponse release(@PathVariable Long reservationId) {
        return inventoryService.release(reservationId);
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable Long reservationId) {
        return inventoryService.getReservation(reservationId);
    }
    @GetMapping("/reservations/order/{orderId}")
    public ReservationResponse getForOrder(@PathVariable Long orderId) {
        return inventoryService.getReservationForOrder(orderId);
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable Long reservationId) {
        return inventoryService.confirm(reservationId);
    }

    @PostMapping("/reservations/{reservationId}/checkout-extension")
    public ReservationResponse extendForCheckout(@PathVariable Long reservationId,
            @Valid @RequestBody ExtendCheckoutReservationRequest request) {
        org.demo.platformsecurity.Access.require(org.demo.platformsecurity.Access.actor().internal()
                && "payment-service".equals(org.demo.platformsecurity.Access.actor().service()));
        return inventoryService.extendForCheckout(reservationId,request);
    }

    @PostMapping("/reservations/{reservationId}/stripe-compensation")
    public ReservationResponse compensateStripe(@PathVariable Long reservationId,@RequestBody java.util.Map<String,Long> command) {
        org.demo.platformsecurity.Access.require(org.demo.platformsecurity.Access.actor().internal()
                && "payment-service".equals(org.demo.platformsecurity.Access.actor().service()));
        return inventoryService.compensateStripe(reservationId,command.get("orderId"));
    }
}
