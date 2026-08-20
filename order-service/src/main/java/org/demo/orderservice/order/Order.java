package org.demo.orderservice.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.demo.orderservice.web.DeliveryAddressRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "total_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "inventory_reservation_id")
    private Long inventoryReservationId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_email", nullable = false, length = 255)
    private String customerEmail;

    @Column(name = "delivery_first_name", nullable = false, length = 100)
    private String deliveryFirstName;

    @Column(name = "delivery_last_name", nullable = false, length = 100)
    private String deliveryLastName;

    @Column(name = "delivery_street", nullable = false, length = 200)
    private String deliveryStreet;

    @Column(name = "delivery_postal_code", nullable = false, length = 20)
    private String deliveryPostalCode;

    @Column(name = "delivery_city", nullable = false, length = 120)
    private String deliveryCity;

    @Column(name = "delivery_country", nullable = false, length = 120)
    private String deliveryCountry;

    @Column(name = "delivery_method", nullable = false, length = 40)
    private String deliveryMethod;

    @Column(name = "delivery_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal deliveryCost;

    @Column(name = "promotion_code", length = 40)
    private String promotionCode;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "fulfillment_type", nullable = false, length = 30)
    private String fulfillmentType;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "delivery_slot", length = 40)
    private String deliverySlot;

    @Column(name = "pickup_location", length = 80)
    private String pickupLocation;

    @Column(name = "invoice_requested", nullable = false)
    private boolean invoiceRequested;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(BigDecimal totalPrice, Long customerId, String customerEmail, DeliveryAddressRequest address) {
        this(totalPrice, customerId, customerEmail, address, "STANDARD_COURIER", BigDecimal.ZERO,
                "legacy-" + UUID.randomUUID(), null, BigDecimal.ZERO, "HOME_DELIVERY", null, null, null);
    }

    public Order(
            BigDecimal totalPrice,
            Long customerId,
            String customerEmail,
            DeliveryAddressRequest address,
            String idempotencyKey
    ) {
        this(totalPrice, customerId, customerEmail, address, "STANDARD_COURIER", BigDecimal.ZERO,
                idempotencyKey, null, BigDecimal.ZERO, "HOME_DELIVERY", null, null, null);
    }

    public Order(
            BigDecimal totalPrice,
            Long customerId,
            String customerEmail,
            DeliveryAddressRequest address,
            String deliveryMethod,
            BigDecimal deliveryCost,
            String idempotencyKey
    ) {
        this(totalPrice, customerId, customerEmail, address, deliveryMethod, deliveryCost,
                idempotencyKey, null, BigDecimal.ZERO, fulfillmentTypeFor(deliveryMethod), null, null, null);
    }

    public Order(
            BigDecimal totalPrice,
            Long customerId,
            String customerEmail,
            DeliveryAddressRequest address,
            String deliveryMethod,
            BigDecimal deliveryCost,
            String idempotencyKey,
            String promotionCode,
            BigDecimal discountAmount,
            String fulfillmentType,
            LocalDate deliveryDate,
            String deliverySlot,
            String pickupLocation
    ) {
        this.totalPrice = totalPrice;
        this.status = OrderStatus.CREATED;
        this.idempotencyKey = idempotencyKey;
        this.customerId = customerId;
        this.customerEmail = customerEmail.trim();
        this.deliveryFirstName = address.firstName().trim();
        this.deliveryLastName = address.lastName().trim();
        this.deliveryStreet = address.street().trim();
        this.deliveryPostalCode = address.postalCode().trim();
        this.deliveryCity = address.city().trim();
        this.deliveryCountry = address.country().trim();
        this.deliveryMethod = deliveryMethod == null || deliveryMethod.isBlank()
                ? "STANDARD_COURIER" : deliveryMethod.trim().toUpperCase();
        this.deliveryCost = deliveryCost == null ? BigDecimal.ZERO : deliveryCost.setScale(2, java.math.RoundingMode.HALF_UP);
        this.promotionCode = promotionCode == null || promotionCode.isBlank() ? null : promotionCode.trim().toUpperCase();
        this.discountAmount = discountAmount == null ? BigDecimal.ZERO : discountAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        this.fulfillmentType = fulfillmentType == null || fulfillmentType.isBlank()
                ? fulfillmentTypeFor(this.deliveryMethod) : fulfillmentType.trim().toUpperCase();
        this.deliveryDate = deliveryDate;
        this.deliverySlot = deliverySlot == null || deliverySlot.isBlank() ? null : deliverySlot.trim();
        this.pickupLocation = pickupLocation == null || pickupLocation.isBlank() ? null : pickupLocation.trim();
        this.invoiceRequested = false;
    }

    private static String fulfillmentTypeFor(String deliveryMethod) {
        return "STORE_PICKUP".equalsIgnoreCase(deliveryMethod) ? "STORE_PICKUP" : "HOME_DELIVERY";
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

    public void addItem(
            Long productId,
            String sku,
            String productName,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice
    ) {
        items.add(new OrderItem(this, productId, sku, productName, quantity, unitPrice, totalPrice));
    }

    public void assignInventoryReservation(Long reservationId) {
        this.inventoryReservationId = reservationId;
    }

    public Long getId() {
        return id;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Long getInventoryReservationId() {
        return inventoryReservationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getCustomerId() { return customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public String getDeliveryFirstName() { return deliveryFirstName; }
    public String getDeliveryLastName() { return deliveryLastName; }
    public String getDeliveryStreet() { return deliveryStreet; }
    public String getDeliveryPostalCode() { return deliveryPostalCode; }
    public String getDeliveryCity() { return deliveryCity; }
    public String getDeliveryCountry() { return deliveryCountry; }
    public String getDeliveryMethod() { return deliveryMethod; }
    public BigDecimal getDeliveryCost() { return deliveryCost; }
    public String getPromotionCode() { return promotionCode; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public String getFulfillmentType() { return fulfillmentType; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public String getDeliverySlot() { return deliverySlot; }
    public String getPickupLocation() { return pickupLocation; }
    public boolean isInvoiceRequested() { return invoiceRequested; }

    public void markInvoiceRequested(boolean invoiceRequested) {
        this.invoiceRequested = invoiceRequested;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeStatus(OrderStatus status) {
        this.status = status;
    }
}
