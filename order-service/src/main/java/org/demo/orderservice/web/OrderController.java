package org.demo.orderservice.web;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.OrderAccess;

import jakarta.validation.Valid;
import org.demo.orderservice.order.OrderService;
import org.demo.orderservice.order.OrderSagaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderSagaService orderSagaService;

    public OrderController(OrderService orderService, OrderSagaService orderSagaService) {
        this.orderService = orderService;
        this.orderSagaService = orderSagaService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @io.swagger.v3.oas.annotations.Parameter(description="Stable checkout key, bound to owner and canonical request. Reuse for retries; changed intent returns 409.",
                    schema=@io.swagger.v3.oas.annotations.media.Schema(minLength=1,maxLength=100,pattern="^[!-~]+$"))
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request, idempotencyKey));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAll(@RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "50") int size, @RequestParam(required = false) org.demo.orderservice.order.OrderStatus status,
            @RequestParam(defaultValue = "") String search) {
        return orderService.list(null, status, null, search, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<OrderResponse>> getForCustomer(@PathVariable Long customerId,
            @RequestParam(defaultValue = "0") long cursor, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) org.demo.orderservice.order.OrderStatus status,
            @RequestParam(required = false) Long productId) {
        Access.customer(customerId);
        return orderService.list(customerId, status, productId, "", new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @GetMapping("/bestsellers")
    public List<BestSellerResponse> bestSellers(@RequestParam(defaultValue = "6") int limit) {
        return orderService.bestSellers(limit);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        orderService.requireOwner(id);
        return orderService.get(id);
    }

    @GetMapping("/{id}/saga")
    public OrderSagaResponse getSaga(@PathVariable Long id) {
        return orderSagaService.getStatus(id);
    }

    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ChangeOrderStatusRequest request
    ) {
        if (Access.actor().internal()) Access.require(request.status() == org.demo.orderservice.order.OrderStatus.PAID
                || request.status() == org.demo.orderservice.order.OrderStatus.CANCELLED);
        return orderService.changeStatus(id, request.status());
    }
}
