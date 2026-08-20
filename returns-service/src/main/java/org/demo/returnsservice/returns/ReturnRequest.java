package org.demo.returnsservice.returns;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "return_requests", uniqueConstraints = @UniqueConstraint(
        name = "uk_return_order_customer_type", columnNames = {"order_id", "customer_id", "request_type"}))
public class ReturnRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "order_id", nullable = false) private Long orderId;
    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "customer_email", nullable = false, length = 255) private String customerEmail;
    @Enumerated(EnumType.STRING) @Column(name = "request_type", nullable = false, length = 20) private ReturnType requestType;
    @Column(nullable = false, length = 120) private String reason;
    @Column(nullable = false, length = 2000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReturnStatus status;
    @Column(name = "moderation_note", length = 1000) private String moderationNote;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ReturnRequest() {}
    public ReturnRequest(Long orderId, Long customerId, String customerEmail, ReturnType requestType, String reason, String description) {
        this.orderId = orderId; this.customerId = customerId; this.customerEmail = customerEmail.trim();
        this.requestType = requestType; this.reason = reason.trim(); this.description = description.trim();
        this.status = ReturnStatus.REQUESTED;
    }
    @PrePersist void created() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void updated() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getCustomerId() { return customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public ReturnType getRequestType() { return requestType; }
    public String getReason() { return reason; }
    public String getDescription() { return description; }
    public ReturnStatus getStatus() { return status; }
    public String getModerationNote() { return moderationNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void changeStatus(ReturnStatus status, String note) { this.status = status; this.moderationNote = note == null || note.isBlank() ? null : note.trim(); }
}
