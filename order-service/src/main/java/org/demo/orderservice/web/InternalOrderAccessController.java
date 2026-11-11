package org.demo.orderservice.web;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.OrderAccess;
import org.demo.orderservice.order.OrderService;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalOrderAccessController {
    private final OrderService orders;
    public InternalOrderAccessController(OrderService orders) { this.orders = orders; }
    @GetMapping("/internal/orders/customer/{customerId}/purchased-products")
    public java.util.List<Long> purchasedProducts(@PathVariable Long customerId,
            @RequestParam java.util.List<Long> productIds) {
        return orders.purchasedProducts(customerId, productIds);
    }
    @GetMapping("/internal/orders/{id}/access")
    public OrderAccess.Ownership access(@PathVariable Long id,
            @RequestHeader(value = Access.ORDER_TOKEN, required = false) String capability) {
        return orders.ownership(id, capability);
    }
}
