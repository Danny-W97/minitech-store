package org.demo.deliveryservice.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "shipments")
public class Shipment {

    public static final String LABEL_CREATED = "LABEL_CREATED";
    public static final String READY_FOR_PICKUP = "READY_FOR_PICKUP";
    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    public static final String DELIVERED = "DELIVERED";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "tracking_number", nullable = false, unique = true, length = 60)
    private String trackingNumber;

    @Column(nullable = false, length = 80)
    private String carrier;

    @Column(name = "method_code", nullable = false, length = 40)
    private String methodCode;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name="progress_revision",nullable=false) private long progressRevision;
    @Column(name="sync_state",nullable=false,length=24) private String syncState="IDLE";
    @Column(name="sync_attempts",nullable=false) private int syncAttempts;
    @Column(name="sync_error",length=80) private String syncError;
    @Column(name="sync_next_attempt_at") private Instant syncNextAttemptAt;
    @Column(name="sync_token",length=36) private String syncToken;
    @Column(name="sync_until") private Instant syncUntil;
    public long getProgressRevision() { return progressRevision; }
    public String getSyncState() { return syncState; }
    public String getSyncError() { return syncError; }
    public int getSyncAttempts() { return syncAttempts; }
    public Instant getSyncNextAttemptAt() { return syncNextAttemptAt; }
    public String getSyncToken() { return syncToken; }
    public boolean syncProcessing() { return syncUntil!=null && syncUntil.isAfter(Instant.now()); }
    public boolean syncDue() { return java.util.Set.of("PENDING","RETRY").contains(syncState) && syncNextAttemptAt!=null && !syncNextAttemptAt.isAfter(Instant.now()) && !syncProcessing(); }
    public void claimSync(String token) { syncToken=token;syncUntil=Instant.now().plusSeconds(120);syncAttempts++; }
    public void syncResult(String state,String code) {
        syncState=state;syncError=code;syncToken=null;syncUntil=null;
        syncNextAttemptAt="RETRY".equals(state) ? Instant.now().plusSeconds(Math.min(60,1L<<Math.min(syncAttempts,6))) : null;
    }
    public void retrySync() { if(!"IDLE".equals(syncState) && !"SYNCED".equals(syncState)) queueSync(); }
    private void queueSync() { syncState="PENDING";syncAttempts=0;syncError=null;syncNextAttemptAt=Instant.now();syncToken=null;syncUntil=null; }

    protected Shipment() {
    }

    public Shipment(Long orderId, String methodCode) {
        this.orderId = orderId;
        this.methodCode = methodCode.trim().toUpperCase(Locale.ROOT);
        this.carrier = carrierFor(this.methodCode);
        this.trackingNumber = "MT-" + this.carrier.substring(0, Math.min(3, this.carrier.length()))
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        this.status = "STORE_PICKUP".equals(this.methodCode) ? READY_FOR_PICKUP : LABEL_CREATED;
    }

    public static String carrierFor(String methodCode) {
        return switch (methodCode.trim().toUpperCase(Locale.ROOT)) {
            case "PARCEL_LOCKER" -> "MiniBox Locker";
            case "STORE_PICKUP" -> "MiniTech Store";
            default -> "MiniTech Courier";
        };
    }

    public void updateStatus(String status) {
        if(this.status.equals(status)) return;
        boolean pickup="STORE_PICKUP".equals(methodCode);
        var ranks=java.util.Map.of(LABEL_CREATED,0,IN_TRANSIT,1,OUT_FOR_DELIVERY,2,DELIVERED,3);
        if(java.util.Set.of(DELIVERED,PICKED_UP,CANCELLED).contains(this.status)
                || !CANCELLED.equals(status) && (pickup ? !PICKED_UP.equals(status)
                    : !ranks.containsKey(status) || ranks.get(status)<ranks.getOrDefault(this.status,99)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Shipment progress cannot move backwards or change delivery mode");
        this.status = status;updatedAt=Instant.now();progressRevision++;queueSync();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getCarrier() { return carrier; }
    public String getMethodCode() { return methodCode; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
