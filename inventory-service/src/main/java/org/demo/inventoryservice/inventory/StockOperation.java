package org.demo.inventoryservice.inventory;

import jakarta.persistence.*;
import java.time.Instant;

/** Immutable receipt/stocktake result, committed in the stock update transaction. */
@Entity
@Table(name="inventory_stock_operations")
@org.hibernate.annotations.Immutable
public class StockOperation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,unique=true,length=36) private String operationId;
    @Column(nullable=false) private Long productId;
    @Column(nullable=false,length=20) private String kind;
    @Column(nullable=false) private int quantity;
    @Column(nullable=false) private int availableBefore;
    @Column(nullable=false) private int availableQuantity;
    @Column(nullable=false) private int reservedQuantity;
    @Column(nullable=false) private int soldQuantity;
    @Column(nullable=false) private long beforeVersion;
    @Column(nullable=false) private long version;
    @Column(nullable=false) private Instant createdAt;
    protected StockOperation() {}
    public StockOperation(String operationId,Long productId,String kind,int before,long beforeVersion,InventoryItem item) {
        this.operationId=operationId;this.productId=productId;this.kind=kind;availableBefore=before;this.beforeVersion=beforeVersion;
        availableQuantity=item.getAvailableQuantity();quantity=availableQuantity-before;reservedQuantity=item.getReservedQuantity();
        soldQuantity=item.getSoldQuantity();version=item.getVersion();createdAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    public Long getId() { return id; }
    public String getOperationId() { return operationId; }
    public Long getProductId() { return productId; }
    public String getKind() { return kind; }
    public int getQuantity() { return quantity; }
    public int getAvailableBefore() { return availableBefore; }
    public int getAvailableQuantity() { return availableQuantity; }
    public int getReservedQuantity() { return reservedQuantity; }
    public int getSoldQuantity() { return soldQuantity; }
    public long getBeforeVersion() { return beforeVersion; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
