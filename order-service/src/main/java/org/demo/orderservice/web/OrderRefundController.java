package org.demo.orderservice.web;

import org.demo.orderservice.order.OrderRefundService;
import org.demo.platformsecurity.Access;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/orders/{id}/stripe-refund")
public class OrderRefundController {
    private final OrderRefundService refunds;
    public OrderRefundController(OrderRefundService refunds) { this.refunds=refunds; }
    @PostMapping("/{step}")
    public OrderResponse update(@PathVariable Long id,@PathVariable String step,@RequestBody OrderRefundService.Command command) {
        Access.require(Access.actor().internal() && "payment-service".equals(Access.actor().service()));
        return switch(step) {
            case "begin" -> refunds.begin(id,command);
            case "complete" -> refunds.complete(id,command);
            default -> throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        };
    }
}
