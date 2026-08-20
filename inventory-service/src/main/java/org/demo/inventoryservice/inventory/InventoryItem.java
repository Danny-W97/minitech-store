package org.demo.inventoryservice.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity;

    @Column(name = "sold_quantity", nullable = false)
    private Integer soldQuantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryItem() {
    }

    public InventoryItem(Long productId, Integer availableQuantity) {
        this.productId = productId;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
        this.soldQuantity = 0;
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

    public void setAvailableQuantity(Integer availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public void reserve(Integer quantity) {
        if (availableQuantity < quantity) {
            throw new InsufficientStockException(productId, availableQuantity, quantity);
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
    }

    public void release(Integer quantity) {
        if (reservedQuantity < quantity) {
            throw new InventoryReservationConflictException("Reserved quantity is lower than requested release");
        }
        reservedQuantity -= quantity;
        availableQuantity += quantity;
    }

    public void confirm(Integer quantity) {
        if (reservedQuantity < quantity) {
            throw new InventoryReservationConflictException("Reserved quantity is lower than requested confirmation");
        }
        reservedQuantity -= quantity;
        soldQuantity += quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getAvailableQuantity() {
        return availableQuantity;
    }

    public Integer getReservedQuantity() {
        return reservedQuantity;
    }

    public Integer getSoldQuantity() {
        return soldQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
