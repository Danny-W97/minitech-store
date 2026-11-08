package org.demo.promotionservice.web;

import org.demo.promotionservice.promotion.*;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.ListWindow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class CouponReservationController {
    public record Release(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_-]{2,39}") String code) {}
    private final CouponReservationService service;
    private final CouponReservationStateStore states;
    public CouponReservationController(CouponReservationService service,CouponReservationStateStore states) { this.service=service;this.states=states; }
    private void requireOrder() { Access.require(Access.actor().internal() && "order-service".equals(Access.actor().service())); }
    @PostMapping("/internal/promotions/orders/{id}/coupon/reserve")
    public CouponReservationStateStore.Response reserve(@PathVariable Long id,@Valid @RequestBody CouponReservationStateStore.Request request) { requireOrder();return service.reserve(id,request); }
    @PostMapping("/internal/promotions/orders/{id}/coupon/release")
    public CouponReservationStateStore.Response release(@PathVariable Long id,@Valid @RequestBody Release request) { requireOrder();return service.release(id,request.code()); }
    @GetMapping("/internal/promotions/orders/{id}/coupon")
    public CouponReservationStateStore.Response get(@PathVariable Long id) {
        Access.require(Access.actor().internal() && java.util.Set.of("order-service","payment-service").contains(Access.actor().service()));return states.get(id);
    }
    @GetMapping("/promotions/codes/reservations")
    public org.springframework.http.ResponseEntity<java.util.List<CouponReservationStateStore.Response>> list(@RequestParam(required=false) String code,
            @RequestParam(required=false) String state,@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="50") int size) {
        Access.require(Access.actor().operator());return states.page(code,state,new ListWindow(cursor,size)).response();
    }
    @GetMapping("/promotions/codes/{code}/capacity") public CouponReservationStateStore.Capacity capacity(@PathVariable String code) { Access.require(Access.actor().operator());return states.capacity(code); }
}
