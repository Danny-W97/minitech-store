package org.demo.orderservice.web;

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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request, idempotencyKey));
    }

    @GetMapping
    public List<OrderResponse> getAll() {
        return orderService.getAll();
    }

    @GetMapping("/customer/{customerId}")
    public List<OrderResponse> getForCustomer(@PathVariable Long customerId) {
        return orderService.getForCustomer(customerId);
    }

    @GetMapping("/bestsellers")
    public List<BestSellerResponse> bestSellers(@RequestParam(defaultValue = "6") int limit) {
        return orderService.bestSellers(limit);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
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
        return orderService.changeStatus(id, request.status());
    }
}
