package org.demo.customerservice.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, length = 255)
    private String actor;

    @Column(nullable = false, length = 120)
    private String resource;

    @Column(nullable = false, length = 40)
    private String outcome;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(length = 1000)
    private String metadata;

    protected AuditEvent() {
    }

    public AuditEvent(
            String eventType,
            String actor,
            String resource,
            String outcome,
            String correlationId,
            String clientIp,
            String userAgent,
            String metadata
    ) {
        this.eventType = eventType;
        this.actor = actor;
        this.resource = resource;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.metadata = metadata;
    }

    @PrePersist
    void onCreate() {
        occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getEventType() { return eventType; }
    public String getActor() { return actor; }
    public String getResource() { return resource; }
    public String getOutcome() { return outcome; }
    public String getCorrelationId() { return correlationId; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public String getMetadata() { return metadata; }
}
