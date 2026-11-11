package org.demo.chatservice.chat;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="chat_entries",uniqueConstraints=@UniqueConstraint(columnNames={"conversation_id","author","request_key"}))
public class ChatEntry {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="conversation_id",nullable=false,updatable=false) private Long conversationId;
    @Column(nullable=false,length=10,updatable=false) private String author;
    @Column(name="initial_message",nullable=false,updatable=false) private boolean initialMessage;
    @Column(name="request_key",nullable=false,length=100,updatable=false) private String requestKey;
    @Column(nullable=false,length=4000,updatable=false) private String message;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    protected ChatEntry() {}
    public ChatEntry(Long conversation,String author,boolean initial,String key,String message) {
        this.conversationId=conversation;this.author=author;initialMessage=initial;requestKey=key;
        this.message=message.trim();createdAt=Instant.now();
    }
    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public String getAuthor() { return author; }
    public boolean isInitialMessage() { return initialMessage; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
