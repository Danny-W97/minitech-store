package org.demo.deliveryservice.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "delivery_methods")
public class DeliveryMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "base_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "free_delivery_from", precision = 19, scale = 2)
    private BigDecimal freeDeliveryFrom;

    @Column(name = "eta_min_days", nullable = false)
    private int etaMinDays;

    @Column(name = "eta_max_days", nullable = false)
    private int etaMaxDays;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeliveryMethod() {
    }

    public DeliveryMethod(
            String code,
            String name,
            String description,
            BigDecimal basePrice,
            BigDecimal freeDeliveryFrom,
            int etaMinDays,
            int etaMaxDays,
            boolean active
    ) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.freeDeliveryFrom = freeDeliveryFrom;
        this.etaMinDays = etaMinDays;
        this.etaMaxDays = etaMaxDays;
        this.active = active;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getBasePrice() { return basePrice; }
    public BigDecimal getFreeDeliveryFrom() { return freeDeliveryFrom; }
    public int getEtaMinDays() { return etaMinDays; }
    public int getEtaMaxDays() { return etaMaxDays; }
    public boolean isActive() { return active; }
}
