package org.demo.orderservice.order;

@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="order.coupons.expiry-enabled",havingValue="true",matchIfMissing=true)
public class OrderCouponExpiryScheduler {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderCouponExpiryScheduler.class);
    private final OrderCouponStateStore states;
    private final OrderCouponService coupons;
    private final org.demo.platformsecurity.BoundedJobs jobs=new org.demo.platformsecurity.BoundedJobs("coupon-expiry",2,40,5000);
    public OrderCouponExpiryScheduler(OrderCouponStateStore states,OrderCouponService coupons) { this.states=states;this.coupons=coupons; }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${order.coupons.expiry-delay-ms:5000}") public void recover() {
        for(Long id:states.due()) jobs.submit(id,() -> { try { coupons.expire(id); } catch(RuntimeException pending) { log.warn("Coupon expiry {} awaiting coordinated cancellation",id); } });
    }
    @jakarta.annotation.PreDestroy void close() { jobs.close(); }
}
