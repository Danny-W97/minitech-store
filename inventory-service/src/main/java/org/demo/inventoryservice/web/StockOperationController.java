package org.demo.inventoryservice.web;

import jakarta.validation.Valid;
import org.demo.inventoryservice.inventory.StockOperationService;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.ListWindow;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.List;

@RestController
@RequestMapping("/inventory")
public class StockOperationController {
    private final StockOperationService operations;
    public StockOperationController(StockOperationService operations) { this.operations=operations; }
    @PostMapping("/items/{productId}/receipts") @ResponseStatus(HttpStatus.CREATED)
    public StockOperationResponse receive(@PathVariable Long productId,@Valid @RequestBody ReceiveStockRequest request) {
        var actor=Access.actor();
        Access.require(actor.operator() || (actor.internal() && "returns-service".equals(actor.service())));
        return operations.receive(productId,request);
    }
    @PatchMapping("/items/{productId}")
    public StockOperationResponse adjust(@PathVariable Long productId,@Valid @RequestBody PatchInventoryItemRequest request) {
        Access.require(Access.actor().operator());return operations.adjust(productId,request);
    }
    @GetMapping("/operations/{operationId}")
    public StockOperationResponse get(@PathVariable String operationId) { Access.require(Access.actor().operator());return operations.get(operationId); }
    @GetMapping("/operations")
    public ResponseEntity<List<StockOperationResponse>> list(@RequestParam(required=false) Long productId,
            @RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="0") long cursor) {
        Access.require(Access.actor().operator());return operations.list(productId,new ListWindow(cursor,size)).response();
    }
}
