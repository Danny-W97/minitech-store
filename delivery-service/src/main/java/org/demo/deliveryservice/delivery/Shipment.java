package org.demo.deliveryservice.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
        this.status = status;
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

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getCarrier() { return carrier; }
    public String getMethodCode() { return methodCode; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
