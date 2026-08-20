package org.demo.billingservice.billing;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "invoice_items", uniqueConstraints = @UniqueConstraint(
        name = "uk_invoice_item_position", columnNames = {"invoice_id", "position_no"}))
public class InvoiceItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "invoice_id", nullable = false) private Invoice invoice;
    @Column(name = "position_no", nullable = false) private Integer positionNo;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(length = 100) private String sku;
    @Column(name = "product_name", nullable = false, length = 255) private String productName;
    @Column(nullable = false) private Integer quantity;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2) private BigDecimal unitPrice;
    @Column(name = "total_price", nullable = false, precision = 19, scale = 2) private BigDecimal totalPrice;

    protected InvoiceItem() {}

    InvoiceItem(Invoice invoice, int positionNo, Long productId, String sku, String productName,
                Integer quantity, BigDecimal unitPrice, BigDecimal totalPrice) {
        this.invoice = invoice;
        this.positionNo = positionNo;
        this.productId = productId;
        this.sku = sku == null || sku.isBlank() ? null : sku.trim();
        this.productName = productName.trim();
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalPrice = totalPrice;
    }

    public Long getId() { return id; }
    public Integer getPositionNo() { return positionNo; }
    public Long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public Integer getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTotalPrice() { return totalPrice; }
}
