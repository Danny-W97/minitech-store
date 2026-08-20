package org.demo.promotionservice.promotion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "promotion_redemptions", uniqueConstraints = @UniqueConstraint(name = "uk_promotion_redemption_code_order", columnNames = {"code", "order_id"}))
public class PromotionRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private Instant redeemedAt;

    protected PromotionRedemption() {
    }

    public PromotionRedemption(String code, Long orderId) {
        this.code = code;
        this.orderId = orderId;
        this.redeemedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public Long getOrderId() { return orderId; }
    public Instant getRedeemedAt() { return redeemedAt; }
}
