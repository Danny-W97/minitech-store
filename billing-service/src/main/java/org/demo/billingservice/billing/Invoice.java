package org.demo.billingservice.billing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices", uniqueConstraints = @UniqueConstraint(name = "uk_invoice_order", columnNames = "order_id"))
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "order_id", nullable = false) private Long orderId;
    @Column(name = "customer_id") private Long customerId;
    @Column(name = "customer_email", nullable = false, length = 255) private String customerEmail;
    @Column(name = "invoice_number", nullable = false, unique = true, length = 40) private String invoiceNumber;
    @Enumerated(EnumType.STRING) @Column(name = "billing_kind", nullable = false, length = 20) private BillingKind billingKind;
    @Column(name = "buyer_name", nullable = false, length = 200) private String buyerName;
    @Column(name = "company_name", length = 200) private String companyName;
    @Column(name = "tax_id", length = 30) private String taxId;
    @Column(name = "street", nullable = false, length = 200) private String street;
    @Column(name = "postal_code", nullable = false, length = 20) private String postalCode;
    @Column(name = "city", nullable = false, length = 120) private String city;
    @Column(name = "country", nullable = false, length = 80) private String country;
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2) private BigDecimal netAmount;
    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 2) private BigDecimal vatAmount;
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2) private BigDecimal grossAmount;
    @Column(name = "delivery_cost", nullable = false, precision = 19, scale = 2) private BigDecimal deliveryCost;
    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2) private BigDecimal discountAmount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private InvoiceStatus status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("positionNo ASC")
    private List<InvoiceItem> items = new ArrayList<>();

    protected Invoice() {}
    public Invoice(Long orderId, Long customerId, String customerEmail, BillingKind billingKind, String buyerName,
                   String companyName, String taxId, String street, String postalCode, String city, String country,
                   BigDecimal netAmount, BigDecimal vatAmount, BigDecimal grossAmount,
                   BigDecimal deliveryCost, BigDecimal discountAmount, String currency) {
        this.orderId = orderId; this.customerId = customerId; this.customerEmail = customerEmail.trim(); this.invoiceNumber = "PENDING";
        this.billingKind = billingKind; this.buyerName = buyerName.trim(); this.companyName = clean(companyName); this.taxId = clean(taxId);
        this.street = street.trim(); this.postalCode = postalCode.trim(); this.city = city.trim(); this.country = country.trim();
        this.netAmount = netAmount; this.vatAmount = vatAmount; this.grossAmount = grossAmount;
        this.deliveryCost = amount(deliveryCost); this.discountAmount = amount(discountAmount);
        this.currency = currency; this.status = InvoiceStatus.ISSUED;
    }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO.setScale(2) : value; }
    @PrePersist void created() { createdAt = Instant.now(); }
    public Long getId() { return id; } public Long getOrderId() { return orderId; } public Long getCustomerId() { return customerId; }
    public String getCustomerEmail() { return customerEmail; } public String getInvoiceNumber() { return invoiceNumber; }
    public BillingKind getBillingKind() { return billingKind; } public String getBuyerName() { return buyerName; } public String getCompanyName() { return companyName; }
    public String getTaxId() { return taxId; } public String getStreet() { return street; } public String getPostalCode() { return postalCode; }
    public String getCity() { return city; } public String getCountry() { return country; } public BigDecimal getNetAmount() { return netAmount; }
    public BigDecimal getVatAmount() { return vatAmount; } public BigDecimal getGrossAmount() { return grossAmount; } public String getCurrency() { return currency; }
    public BigDecimal getDeliveryCost() { return deliveryCost; } public BigDecimal getDiscountAmount() { return discountAmount; }
    public List<InvoiceItem> getItems() { return List.copyOf(items); }
    public InvoiceStatus getStatus() { return status; } public Instant getCreatedAt() { return createdAt; }
    public void assignNumber(String number) { this.invoiceNumber = number; }
    public void addItem(Long productId, String sku, String productName, Integer quantity,
                        BigDecimal unitPrice, BigDecimal totalPrice) {
        items.add(new InvoiceItem(this, items.size() + 1, productId, sku, productName, quantity, unitPrice, totalPrice));
    }
    public void backfillOrderBreakdown(BigDecimal deliveryCost, BigDecimal discountAmount) {
        if (!items.isEmpty()) return;
        this.deliveryCost = amount(deliveryCost);
        this.discountAmount = amount(discountAmount);
    }
}
