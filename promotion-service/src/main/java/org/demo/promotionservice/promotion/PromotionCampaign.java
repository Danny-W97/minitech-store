package org.demo.promotionservice.promotion;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "promotion_campaigns")
public class PromotionCampaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 60) private String code;
    @Column(nullable = false, length = 160) private String name;
    @Column(name = "min_discount_percent", nullable = false) private int minDiscountPercent;
    @Column(name = "max_discount_percent", nullable = false) private int maxDiscountPercent;
    @Column(name = "promotion_count", nullable = false) private int promotionCount;
    @Column(name = "new_product_count", nullable = false) private int newProductCount;
    @Column(nullable = false) private boolean active;
    @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
    @Column(name = "valid_to", nullable = false) private LocalDate validTo;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected PromotionCampaign() {}
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getMinDiscountPercent() { return minDiscountPercent; }
    public int getMaxDiscountPercent() { return maxDiscountPercent; }
    public int getPromotionCount() { return promotionCount; }
    public int getNewProductCount() { return newProductCount; }
    public boolean isActive() { return active; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
}
