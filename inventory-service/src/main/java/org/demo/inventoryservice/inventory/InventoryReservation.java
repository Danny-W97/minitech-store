package org.demo.inventoryservice.inventory;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InventoryReservationItem> items = new ArrayList<>();

    protected InventoryReservation() {
    }

    public InventoryReservation(Long orderId, Instant expiresAt) {
        this.orderId = orderId;
        this.status = ReservationStatus.RESERVED;
        this.expiresAt = expiresAt;
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

    public void addItem(Long productId, Integer quantity) {
        items.add(new InventoryReservationItem(this, productId, quantity));
    }

    public void changeStatus(ReservationStatus status) {
        this.status = status;
    }

    public void extendForCheckout(Instant newExpiry, Instant now) {
        if (status != ReservationStatus.RESERVED || !expiresAt.isAfter(now)) {
            throw new InventoryReservationConflictException("Only an active reservation can be extended for checkout");
        }
        if (newExpiry == null || !newExpiry.isAfter(now))
            throw new InventoryReservationConflictException("Checkout expiry must be in the future");
        if (newExpiry.isAfter(expiresAt)) expiresAt = newExpiry;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<InventoryReservationItem> getItems() {
        return items;
    }
}
