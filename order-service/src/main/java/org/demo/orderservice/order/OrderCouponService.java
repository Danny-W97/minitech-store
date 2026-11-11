package org.demo.orderservice.order;

import org.demo.orderservice.promotion.PromotionClient;
import org.springframework.stereotype.Service;

@Service
public class OrderCouponService {
    private final OrderCouponStateStore states;
    private final PromotionClient promotion;
    private final OrderSagaService sagas;
    public OrderCouponService(OrderCouponStateStore states,PromotionClient promotion,OrderSagaService sagas) { this.states=states;this.promotion=promotion;this.sagas=sagas; }
    public PromotionClient.CouponReservation status(Long id) { states.snapshot(id);return promotion.reservation(id); }
    public PromotionClient.CouponReservation reconcile(Long id) {
        var snapshot=states.snapshot(id);
        if(!snapshot.reservable()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Cancelled or refunded order cannot reserve a coupon");
        var result=promotion.reserve(snapshot.code(),id,snapshot.subtotal(),snapshot.discount(),snapshot.acceptedAt(),snapshot.expiresAt());
        states.armLegacy(id,result.expiresAt());return result;
    }
    public void expire(Long id) { if(states.claimExpiry(id)) sagas.compensate(id); }
}
