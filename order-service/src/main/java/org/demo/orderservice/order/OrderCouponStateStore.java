package org.demo.orderservice.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.List;

@Service
public class OrderCouponStateStore {
    public record Snapshot(Long id,String code,java.math.BigDecimal subtotal,java.math.BigDecimal discount,Instant acceptedAt,Instant expiresAt,boolean reservable) {}
    private final OrderRepository orders;
    private final long ttl;
    public OrderCouponStateStore(OrderRepository orders,@Value("${order.coupons.hold-ttl-seconds:2400}") long ttl) {
        if(ttl<60 || ttl>86400) throw new IllegalArgumentException("Coupon TTL must be between 60 seconds and one day");this.orders=orders;this.ttl=ttl;
    }
    @Transactional(readOnly=true) public Snapshot snapshot(Long id) {
        var order=orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        if(order.getPromotionCode()==null || order.getPromotionCode().isBlank())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Order has no reconcilable coupon");
        var expiresAt=order.getCouponExpiresAt();
        if(expiresAt==null && (order.getStatus()==OrderStatus.CREATED || order.getStatus()==OrderStatus.CONFIRMED) && order.getDeferredPaymentMethod()==null)
            expiresAt=Instant.now().plusSeconds(ttl);
        return new Snapshot(id,order.getPromotionCode(),order.getTotalPrice().add(order.getDiscountAmount()).subtract(order.getDeliveryCost()),order.getDiscountAmount(),order.getCreatedAt(),expiresAt,
                order.getStatus()!=OrderStatus.CANCELLED && order.getRefundPaymentId()==null);
    }
    @Transactional public void armLegacy(Long id,Instant deadline) {
        var order=orders.lockId(id).orElseThrow();
        if(order.getStatus()==OrderStatus.CANCELLED || order.getRefundPaymentId()!=null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Coupon reconciliation raced with cancellation or refund");
        order.verifyCouponReservation();
        if(deadline!=null && (order.getStatus()==OrderStatus.CREATED || order.getStatus()==OrderStatus.CONFIRMED) && order.getDeferredPaymentMethod()==null && order.getRefundPaymentId()==null)
            order.armCouponExpiry(deadline);
    }
    @Transactional(readOnly=true) public List<Long> due() { return orders.expiredCouponOrders(Instant.now(),org.springframework.data.domain.PageRequest.of(0,20)); }
    @Transactional public boolean claimExpiry(Long id) {
        var order=orders.lockId(id).orElseThrow();var now=Instant.now();
        if(order.getCouponExpiresAt()==null || order.getCouponExpiresAt().isAfter(now) || order.getCouponNextAttemptAt()==null || order.getCouponNextAttemptAt().isAfter(now)
                || order.getDeferredPaymentMethod()!=null || order.getRefundPaymentId()!=null || order.isLifecycleReviewRequired()
                || !(order.getStatus()==OrderStatus.CREATED || order.getStatus()==OrderStatus.CONFIRMED)) return false;
        order.postponeCouponExpiry();return true;
    }
}
