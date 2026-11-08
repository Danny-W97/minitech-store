package org.demo.orderservice.web;

import org.demo.orderservice.order.OrderShipmentService;
import org.demo.platformsecurity.Access;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderShipmentController {
    private final OrderShipmentService service;
    public OrderShipmentController(OrderShipmentService service) { this.service=service; }
    @PostMapping("/internal/orders/{id}/shipment-progress")
    public OrderShipmentService.Result apply(@PathVariable Long id,@Valid @RequestBody OrderShipmentService.Command command) {
        Access.require(Access.actor().internal() && "delivery-service".equals(Access.actor().service()));
        return service.apply(id,command);
    }
}
