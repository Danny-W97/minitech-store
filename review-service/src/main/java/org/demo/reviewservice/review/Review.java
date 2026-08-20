package org.demo.reviewservice.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "reviews", uniqueConstraints = @UniqueConstraint(
        name = "uk_review_customer_order_product",
        columnNames = {"customer_id", "order_id", "product_id"}))
public class Review {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "order_id", nullable = false) private Long orderId;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(nullable = false) private Integer rating;
    @Column(nullable = false, length = 120) private String title;
    @Column(nullable = false, length = 2000) private String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReviewStatus status;
    @Column(name = "moderation_note", length = 500) private String moderationNote;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Review() {}

    public Review(Long customerId, Long orderId, Long productId, Integer rating, String title, String content) {
        this.customerId = customerId;
        this.orderId = orderId;
        this.productId = productId;
        this.rating = rating;
        this.title = title.trim();
        this.content = content.trim();
        this.status = ReviewStatus.PENDING;
    }

    @PrePersist
    void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public Long getOrderId() { return orderId; }
    public Long getProductId() { return productId; }
    public Integer getRating() { return rating; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public ReviewStatus getStatus() { return status; }
    public String getModerationNote() { return moderationNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void moderate(ReviewStatus status, String note) {
        this.status = status;
        this.moderationNote = note == null || note.isBlank() ? null : note.trim();
    }
}
