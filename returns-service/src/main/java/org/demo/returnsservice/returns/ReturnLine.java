package org.demo.returnsservice.returns;

import jakarta.persistence.*;

@Entity @Table(name="return_lines",uniqueConstraints=@UniqueConstraint(columnNames={"return_id","order_item_id"}))
public class ReturnLine {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="return_id") private ReturnRequest request;
    @Column(name="order_item_id",nullable=false) private Long orderItemId;
    @Column(name="product_id",nullable=false) private Long productId;
    @Column(name="product_name",nullable=false,length=255) private String productName;
    @Column(name="ordered_quantity",nullable=false) private int orderedQuantity;
    @Column(nullable=false) private int quantity;
    protected ReturnLine() {}
    public ReturnLine(ReturnRequest request,ReturnOrderClient.Line source,int quantity) {
        this.request=request;orderItemId=source.id();productId=source.productId();productName=source.productName();orderedQuantity=source.quantity();this.quantity=quantity;
    }
    public Long getOrderItemId() { return orderItemId; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public int getOrderedQuantity() { return orderedQuantity; }
    public int getQuantity() { return quantity; }
}
