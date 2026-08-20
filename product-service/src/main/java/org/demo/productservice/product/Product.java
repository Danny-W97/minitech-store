package org.demo.productservice.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 80)
    private String category;

    @Column(nullable = false, length = 120)
    private String manufacturer;

    @Column(nullable = false, length = 120)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "technical_parameters", nullable = false)
    private Map<String, Object> technicalParameters;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public Product(
            String sku,
            String name,
            String category,
            String manufacturer,
            String model,
            Map<String, Object> technicalParameters,
            BigDecimal price,
            boolean active,
            String imageUrl
    ) {
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.manufacturer = manufacturer;
        this.model = model;
        this.technicalParameters = copyParameters(technicalParameters);
        this.price = price;
        this.active = active;
        this.imageUrl = imageUrl;
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

    public void update(
            String sku,
            String name,
            String category,
            String manufacturer,
            String model,
            Map<String, Object> technicalParameters,
            BigDecimal price,
            Boolean active,
            String imageUrl
    ) {
        if (sku != null) this.sku = sku;
        if (name != null) this.name = name;
        if (category != null) this.category = category;
        if (manufacturer != null) this.manufacturer = manufacturer;
        if (model != null) this.model = model;
        if (technicalParameters != null) this.technicalParameters = copyParameters(technicalParameters);
        if (price != null) this.price = price;
        if (active != null) this.active = active;
        if (imageUrl != null) this.imageUrl = imageUrl;
    }

    private Map<String, Object> copyParameters(Map<String, Object> parameters) {
        return parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getModel() {
        return model;
    }

    public Map<String, Object> getTechnicalParameters() {
        return technicalParameters;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
