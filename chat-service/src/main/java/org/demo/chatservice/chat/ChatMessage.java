package org.demo.chatservice.chat;

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

import java.time.Instant;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "customer_email", nullable = false, length = 255)
    private String customerEmail;

    @Column(nullable = false, length = 160)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatMessageStatus status;

    @Column(name = "notification_status", nullable = false, length = 20)
    private String notificationStatus;

    @Column(name = "reply_message", length = 4000)
    private String replyMessage;

    @Column(name = "reply_notification_status", length = 20)
    private String replyNotificationStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    protected ChatMessage() {
    }

    public ChatMessage(String customerName, String customerEmail, String subject, String message) {
        this.customerName = customerName.trim();
        this.customerEmail = customerEmail.trim().toLowerCase();
        this.subject = subject == null || subject.isBlank() ? "Pytanie ze sklepu" : subject.trim();
        this.message = message.trim();
        this.status = ChatMessageStatus.NEW;
        this.notificationStatus = "PENDING";
        this.replyNotificationStatus = null;
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

    public void markNotification(String status) { this.notificationStatus = status; }

    public void addReply(String reply) {
        this.replyMessage = reply.trim();
        this.replyNotificationStatus = "PENDING";
        this.status = ChatMessageStatus.ANSWERED;
        this.repliedAt = Instant.now();
    }

    public void markReplyNotification(String status) { this.replyNotificationStatus = status; }
    public Long getId() { return id; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public String getSubject() { return subject; }
    public String getMessage() { return message; }
    public ChatMessageStatus getStatus() { return status; }
    public String getNotificationStatus() { return notificationStatus; }
    public String getReplyMessage() { return replyMessage; }
    public String getReplyNotificationStatus() { return replyNotificationStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getRepliedAt() { return repliedAt; }
}
