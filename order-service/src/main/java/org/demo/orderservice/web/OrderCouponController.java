package org.demo.orderservice.web;

import org.demo.orderservice.order.OrderCouponService;
import org.demo.orderservice.promotion.PromotionClient;
import org.demo.platformsecurity.Access;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderCouponController {
    private final OrderCouponService coupons;
    public OrderCouponController(OrderCouponService coupons) { this.coupons=coupons; }
    @GetMapping("/orders/{id}/coupon") public PromotionClient.CouponReservation status(@PathVariable Long id) { Access.require(Access.actor().operator());return coupons.status(id); }
    @PostMapping("/orders/{id}/coupon/reconcile") public PromotionClient.CouponReservation reconcile(@PathVariable Long id) { Access.require(Access.actor().operator());return coupons.reconcile(id); }
}
