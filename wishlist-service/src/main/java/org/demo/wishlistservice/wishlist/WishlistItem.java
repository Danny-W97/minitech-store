package org.demo.wishlistservice.wishlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "wishlist_items", uniqueConstraints = @UniqueConstraint(
        name = "uk_wishlist_customer_product", columnNames = {"customer_id", "product_id"}))
public class WishlistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected WishlistItem() {}
    public WishlistItem(Long customerId, Long productId) {
        this.customerId = customerId;
        this.productId = productId;
    }
    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public Long getProductId() { return productId; }
    public Instant getCreatedAt() { return createdAt; }
}
