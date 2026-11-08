package org.demo.productservice.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "product_price_history_events", uniqueConstraints = @UniqueConstraint(
        name = "uk_product_price_history_revision", columnNames = {"product_id", "rotation_date", "price_revision", "layout_revision", "source_type"}))
public class ProductPriceHistoryEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(name = "price_revision", nullable = false) private long priceRevision;
    @Column(name = "layout_revision", nullable = false) private long layoutRevision;
    @Column(name = "source_type", nullable = false, length = 24) private String sourceType;
    @Column(name = "campaign_code", nullable = false, length = 60) private String campaignCode;
    @Column(name = "regular_price", nullable = false, precision = 19, scale = 2) private BigDecimal regularPrice;
    @Column(name = "effective_price", nullable = false, precision = 19, scale = 2) private BigDecimal effectivePrice;
    @Column(name = "discount_percent", nullable = false) private int discountPercent;
    @Column(name = "rotation_date", nullable = false) private LocalDate rotationDate;
    @Column(name = "catalog_index", nullable = false) private int catalogIndex;
    @Column(name = "catalog_size", nullable = false) private int catalogSize;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;

    protected ProductPriceHistoryEvent() {}

    public ProductPriceHistoryEvent(CatalogProduct entry, DailyCatalogRotation.ProductOffer offer,
                                    LocalDate date, String sourceType) {
        this.productId = entry.product().getId();
        this.priceRevision = entry.product().getPriceRevision();
        this.layoutRevision = entry.layoutRevision();
        this.sourceType = sourceType;
        this.campaignCode = "DAILY_STORE_ROTATION";
        this.regularPrice = offer.regularPrice();
        this.effectivePrice = offer.price();
        this.discountPercent = offer.discountPercent();
        this.rotationDate = date;
        this.catalogIndex = entry.index();
        this.catalogSize = entry.size();
        this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public long getPriceRevision() { return priceRevision; }
    public long getLayoutRevision() { return layoutRevision; }
    public String getSourceType() { return sourceType; }
    public String getCampaignCode() { return campaignCode; }
    public BigDecimal getRegularPrice() { return regularPrice; }
    public BigDecimal getEffectivePrice() { return effectivePrice; }
    public int getDiscountPercent() { return discountPercent; }
    public LocalDate getRotationDate() { return rotationDate; }
    public int getCatalogIndex() { return catalogIndex; }
    public int getCatalogSize() { return catalogSize; }
    public Instant getRecordedAt() { return recordedAt; }
}
