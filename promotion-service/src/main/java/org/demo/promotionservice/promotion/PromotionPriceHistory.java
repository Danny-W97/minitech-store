package org.demo.promotionservice.promotion;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "promotion_price_history")
public class PromotionPriceHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(name = "campaign_code", nullable = false, length = 60) private String campaignCode;
    @Column(name = "regular_price", nullable = false, precision = 19, scale = 2) private BigDecimal regularPrice;
    @Column(name = "promotional_price", nullable = false, precision = 19, scale = 2) private BigDecimal promotionalPrice;
    @Column(name = "discount_percent", nullable = false) private int discountPercent;
    @Column(name = "rotation_date", nullable = false) private java.time.LocalDate rotationDate;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;
    protected PromotionPriceHistory() {}
    public PromotionPriceHistory(Long productId, String campaignCode, BigDecimal regularPrice,
                                 BigDecimal promotionalPrice, int discountPercent,
                                 java.time.LocalDate rotationDate) {
        this.productId = productId;
        this.campaignCode = campaignCode;
        this.regularPrice = regularPrice;
        this.promotionalPrice = promotionalPrice;
        this.discountPercent = discountPercent;
        this.rotationDate = rotationDate;
        this.recordedAt = Instant.now();
    }
    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getCampaignCode() { return campaignCode; }
    public BigDecimal getRegularPrice() { return regularPrice; }
    public BigDecimal getPromotionalPrice() { return promotionalPrice; }
    public int getDiscountPercent() { return discountPercent; }
    public java.time.LocalDate getRotationDate() { return rotationDate; }
    public Instant getRecordedAt() { return recordedAt; }
}
