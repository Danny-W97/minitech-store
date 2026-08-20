package org.demo.promotionservice.promotion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "promotion_codes")
public class PromotionCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 40) private String code;
    @Column(nullable = false, length = 200) private String description;
    @Column(name = "discount_percent", nullable = false) private int discountPercent;
    @Column(name = "minimum_order_value", nullable = false) private java.math.BigDecimal minimumOrderValue;
    @Column(name = "maximum_discount_amount") private java.math.BigDecimal maximumDiscountAmount;
    @Column(name = "max_redemptions") private Integer maxRedemptions;
    @Column(name = "redeemed_count", nullable = false) private int redeemedCount;
    @Column(nullable = false) private boolean active;
    @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
    @Column(name = "valid_to", nullable = false) private LocalDate validTo;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected PromotionCode() {}

    public PromotionCode(String code, String description, int discountPercent,
                          Integer maxRedemptions, LocalDate validFrom, LocalDate validTo,
                          boolean active) {
        this(code, description, discountPercent, maxRedemptions, validFrom, validTo, active,
                java.math.BigDecimal.ZERO, null);
    }

    public PromotionCode(String code, String description, int discountPercent,
                          Integer maxRedemptions, LocalDate validFrom, LocalDate validTo,
                          boolean active, java.math.BigDecimal minimumOrderValue,
                          java.math.BigDecimal maximumDiscountAmount) {
        this.code = code;
        this.description = description;
        this.discountPercent = discountPercent;
        this.minimumOrderValue = minimumOrderValue == null ? java.math.BigDecimal.ZERO : minimumOrderValue;
        this.maximumDiscountAmount = maximumDiscountAmount;
        this.maxRedemptions = maxRedemptions;
        this.redeemedCount = 0;
        this.active = active;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public int getDiscountPercent() { return discountPercent; }
    public java.math.BigDecimal getMinimumOrderValue() { return minimumOrderValue; }
    public java.math.BigDecimal getMaximumDiscountAmount() { return maximumDiscountAmount; }
    public Integer getMaxRedemptions() { return maxRedemptions; }
    public int getRedeemedCount() { return redeemedCount; }
    public void incrementRedeemedCount() { redeemedCount++; }
    public boolean isActive() { return active; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
}
