package org.demo.returnsservice.web;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.OrderAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.demo.returnsservice.returns.ReturnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/returns")
@Tag(name = "Returns", description = "Returns and complaints")
public class ReturnController {
    private final ReturnService service;
    public ReturnController(ReturnService service) { this.service = service; }
    @PostMapping @Operation(summary = "Create a return or complaint",description="One immutable case per order/customer/type. Omitted items select all order lines. Returns require a paid COMPLETED purchase; complaints allow PAID/SHIPPED/COMPLETED. Approval does not refund money or stock.")
    public ResponseEntity<ReturnResponse> create(@Valid @RequestBody CreateReturnRequest request) {
        Access.customer(request.customerId());
        if (!Access.actor().operator()) Access.require(request.customerEmail().equalsIgnoreCase(Access.actor().email()));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
    @GetMapping("/customer/{customerId}") public ResponseEntity<List<ReturnResponse>> customer(@PathVariable @Positive Long customerId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        Access.customer(customerId);
        return service.list(customerId, null, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }
    @GetMapping public ResponseEntity<List<ReturnResponse>> all(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) org.demo.returnsservice.returns.ReturnStatus status) {
        return service.list(null, status, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }
    @GetMapping("/{id}") public ReturnResponse get(@PathVariable @Positive Long id) { ReturnResponse response = service.get(id); Access.customer(response.customerId()); return response; }
    @PatchMapping("/{id}/status") @Operation(description="REQUESTED -> UNDER_REVIEW -> APPROVED or REJECTED. Expected version, stable operation UUID and note are required. COMPLETED is blocked pending separate domain reconciliation.")
    public ReturnResponse status(@PathVariable @Positive Long id, @Valid @RequestBody ChangeReturnStatusRequest request) {
        Access.require(Access.actor().operator());return service.changeStatus(id, request);
    }
    @PostMapping("/{id}/reconcile") public ReturnResponse reconcile(@PathVariable Long id,@Valid @RequestBody ReconcileReturnRequest request) {
        Access.require(Access.actor().operator());return service.reconcile(id,request);
    }
    @PostMapping("/{id}/reconcile-effects")
    @Operation(summary = "Reconcile domain effects for an approved return/complaint and mark COMPLETED",
            description = "Triggers stock restock in inventory-service, credit note in billing-service, and payment refund in payment-service.")
    public ReturnResponse reconcileEffects(@PathVariable @Positive Long id, @Valid @RequestBody ReconcileReturnRequest request) {
        Access.require(Access.actor().operator());
        return service.reconcileEffects(id, request);
    }
    @GetMapping("/{id}/history") public ResponseEntity<List<ReturnEventResponse>> history(@PathVariable Long id,
            @RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="50") int size) {
        Access.require(Access.actor().operator());return service.history(id,new org.demo.platformsecurity.ListWindow(cursor,size)).response();
    }
}
