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
    @jakarta.persistence.Version private long version;
    public long getVersion() { return version; }

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
        if(availableQuantity==null || availableQuantity<0 || (long)availableQuantity+reservedQuantity>Integer.MAX_VALUE)
            throw new InventoryReservationConflictException("Stock exceeds supported on-hand capacity");
        this.availableQuantity = availableQuantity;
    }

    public void receive(int quantity) {
        if(quantity<=0) throw new IllegalArgumentException("Receipt quantity must be positive");
        if((long)availableQuantity+reservedQuantity+quantity>Integer.MAX_VALUE)
            throw new InventoryReservationConflictException("Stock exceeds supported on-hand capacity");
        availableQuantity=Math.addExact(availableQuantity,quantity);
    }

    public void reserve(Integer quantity) {
        if (availableQuantity < quantity) {
            throw new InsufficientStockException(productId, availableQuantity, quantity);
        }
        int nextReserved=checkedAdd(reservedQuantity,quantity);
        availableQuantity -= quantity;
        reservedQuantity = nextReserved;
    }

    public void release(Integer quantity) {
        if (reservedQuantity < quantity) {
            throw new InventoryReservationConflictException("Reserved quantity is lower than requested release");
        }
        int nextAvailable=checkedAdd(availableQuantity,quantity);
        reservedQuantity -= quantity;
        availableQuantity = nextAvailable;
    }

    public void confirm(Integer quantity) {
        if (reservedQuantity < quantity) {
            throw new InventoryReservationConflictException("Reserved quantity is lower than requested confirmation");
        }
        int nextSold=checkedAdd(soldQuantity,quantity);
        reservedQuantity -= quantity;
        soldQuantity = nextSold;
    }

    public void reverseUnfulfilledSale(int quantity) {
        if(quantity<=0 || soldQuantity<quantity) throw new InventoryReservationConflictException("Sale cannot be compensated");
        int nextAvailable=checkedAdd(availableQuantity,quantity);
        if((long)nextAvailable+reservedQuantity>Integer.MAX_VALUE) throw new InventoryReservationConflictException("Stock capacity exceeded");
        soldQuantity-=quantity;availableQuantity=nextAvailable;
    }

    public Long getProductId() {
        return productId;
    }
    private int checkedAdd(int current,int quantity) {
        try { return Math.addExact(current,quantity); }
        catch(ArithmeticException overflow) { throw new InventoryReservationConflictException("Stock counter capacity exceeded"); }
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
