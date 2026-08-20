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

    @PostMapping("/items")
    public ResponseEntity<InventoryItemResponse> createItem(
            @Valid @RequestBody CreateInventoryItemRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createItem(request));
    }

    @PatchMapping("/items/{productId}")
    public InventoryItemResponse patchItem(
            @PathVariable Long productId,
            @Valid @RequestBody PatchInventoryItemRequest request
    ) {
        return inventoryService.patchItem(productId, request);
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

    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable Long reservationId) {
        return inventoryService.confirm(reservationId);
    }
}
