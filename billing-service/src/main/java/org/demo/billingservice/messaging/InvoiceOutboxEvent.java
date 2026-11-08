package org.demo.billingservice.messaging;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="invoice_outbox_events")
public class InvoiceOutboxEvent {
    @Column(name="order_id") private Long orderId;
    @Column(name="privacy_safe",nullable=false) private boolean privacySafe;
    public void privacySafe(){privacySafe=true;}
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="event_id",nullable=false,unique=true,length=128) private String eventId;
    @Column(columnDefinition="text") private String payload;
    @Column(name="payload_compacted_at") private Instant payloadCompactedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private InvoiceOutboxStatus status;
    @Column(nullable=false) private int attempts;
    @Column(name="last_error",length=500) private String lastError;
    @Column(name="next_attempt_at",nullable=false) private Instant nextAttemptAt;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="published_at") private Instant publishedAt;
    @Column(name="processing_token",length=36) private String processingToken;
    @Column(name="processing_until") private Instant processingUntil;
    protected InvoiceOutboxEvent() {}
    public InvoiceOutboxEvent(String eventId,Map<String,String> fields) {
        this.eventId=eventId;orderId=fields.containsKey("orderId")?Long.valueOf(fields.get("orderId")):null;var values=new LinkedHashMap<>(fields);values.put("eventId",eventId);
        payload=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(values);
        status=InvoiceOutboxStatus.PENDING;createdAt=Instant.now();nextAttemptAt=createdAt;
    }
    @SuppressWarnings("unchecked") public Map<String,String> fields() {
        if(payload==null || payloadCompactedAt!=null) throw new IllegalStateException("Published invoice event payload was compacted");
        return tools.jackson.databind.json.JsonMapper.builder().build().readValue(payload,Map.class);
    }
    public boolean isPayloadCompacted() { return payloadCompactedAt!=null; }
    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public InvoiceOutboxStatus getStatus() { return status; }
    public String getProcessingToken() { return processingToken; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public boolean processing() { return processingUntil!=null && processingUntil.isAfter(Instant.now()); }
    public void claim(String token,Instant until) { processingToken=token;processingUntil=until; }
    public void release() { processingToken=null;processingUntil=null; }
    public void markPublished() { status=InvoiceOutboxStatus.PUBLISHED;publishedAt=Instant.now();lastError=null; }
    public void markFailed(String error) { status=InvoiceOutboxStatus.FAILED;attempts++;lastError=error;nextAttemptAt=Instant.now().plusSeconds(Math.min(60,1L<<Math.min(attempts,6))); }
}
