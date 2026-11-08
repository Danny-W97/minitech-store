package org.demo.notificationservice.messaging;

import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="event_inbox")
public class EventReceipt implements org.springframework.data.domain.Persistable<String> {
    @Id @Column(name="event_id",length=128) private String eventId;
    @Column(name="order_id") private Long orderId;
    public EventReceipt forOrder(long id){orderId=id;return this;}
    @Column(name="payload_hash",nullable=false,length=64) private String payloadHash;
    @Column(nullable=false,length=20) private String status;
    @Column(name="related_id") private Long notificationId;
    @Column(length=500) private String error;
    @Column(columnDefinition="text") private String payload;
    @Column(name="diagnostics_compacted_at") private Instant diagnosticsCompactedAt;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
    protected EventReceipt() {}
    public EventReceipt(String id,String hash,String status,Long notificationId,String error,String payload) {
        eventId=id;payloadHash=hash;this.status=status;this.notificationId=notificationId;
        this.error=error==null ? null : error.substring(0,Math.min(500,error.length()));this.payload=payload;receivedAt=Instant.now();
    }
    public String getId() { return eventId; }
    public EventReceipt alias(String alias,String fingerprint){var copy=new EventReceipt(alias,fingerprint,status,notificationId,null,null);copy.orderId=orderId;return copy;}
    public boolean isNew() { return true; }
    public String getPayloadHash() { return payloadHash; }
    public String getStatus() { return status; }
    public boolean isDiagnosticsCompacted() { return diagnosticsCompactedAt != null; }
}
