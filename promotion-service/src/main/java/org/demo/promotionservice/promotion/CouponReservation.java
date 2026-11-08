package org.demo.promotionservice.promotion;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** One immutable coupon command per order, including a tombstone after release. */
@Entity @Table(name="coupon_reservations")
public class CouponReservation {
    @Id @Column(name="order_id") private Long orderId;
    @Version @Column(nullable=false) private Long version;
    @Column(nullable=false,length=40) private String code;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal subtotal;
    @Column(name="discount_amount",nullable=false,precision=19,scale=2) private BigDecimal discountAmount;
    @Column(nullable=false,length=16) private String state;
    @Column(name="accepted_at") private Instant acceptedAt;
    @Column(name="expires_at") private Instant expiresAt;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected CouponReservation() {}
    public CouponReservation(Long id,String code,BigDecimal subtotal,BigDecimal discount,String state,Instant acceptedAt,Instant expiresAt) {
        orderId=id;this.code=code;this.subtotal=subtotal.setScale(2,java.math.RoundingMode.UNNECESSARY);discountAmount=discount.setScale(2,java.math.RoundingMode.UNNECESSARY);this.state=state;
        this.acceptedAt=acceptedAt==null?null:acceptedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.expiresAt=expiresAt==null?null:expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        createdAt=Instant.now();updatedAt=createdAt;
    }
    public Long getOrderId() { return orderId; }
    public String getCode() { return code; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public String getState() { return state; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void release() { if(!"CONSUMED".equals(state)) { state="RELEASED";updatedAt=Instant.now(); } }
    public void consume() { state="CONSUMED";updatedAt=Instant.now(); }
}
