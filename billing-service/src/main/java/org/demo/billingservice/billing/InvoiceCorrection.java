package org.demo.billingservice.billing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "invoice_corrections",uniqueConstraints={
        @UniqueConstraint(name="uk_correction_request",columnNames={"invoice_id","idempotency_key"}),
        @UniqueConstraint(name="uk_correction_version",columnNames={"invoice_id","ledger_version"})})
public class InvoiceCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "correction_number", nullable = false, unique = true, length = 40)
    private String correctionNumber;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "original_net", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalNet;

    @Column(name = "original_vat", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalVat;

    @Column(name = "original_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalGross;

    @Column(name = "correction_net", nullable = false, precision = 19, scale = 2)
    private BigDecimal correctionNet;

    @Column(name = "correction_vat", nullable = false, precision = 19, scale = 2)
    private BigDecimal correctionVat;

    @Column(name = "correction_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal correctionGross;

    @Column(name = "final_net", nullable = false, precision = 19, scale = 2)
    private BigDecimal finalNet;

    @Column(name = "final_vat", nullable = false, precision = 19, scale = 2)
    private BigDecimal finalVat;

    @Column(name = "final_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal finalGross;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name="idempotency_key",length=100) private String idempotencyKey;
    @Column(name="ledger_version") private Long ledgerVersion;
    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getLedgerVersion() { return ledgerVersion; }
    public void bindLedger(String key,long version) {
        if(idempotencyKey!=null || ledgerVersion!=null) throw new IllegalStateException("Correction is immutable");
        idempotencyKey=key;ledgerVersion=version;
    }

    protected InvoiceCorrection() {}

    public InvoiceCorrection(Invoice invoice, String correctionNumber, String reason,
                             BigDecimal originalNet, BigDecimal originalVat, BigDecimal originalGross,
                             BigDecimal correctionNet, BigDecimal correctionVat, BigDecimal correctionGross,
                             BigDecimal finalNet, BigDecimal finalVat, BigDecimal finalGross) {
        this.invoice = invoice;
        this.orderId = invoice.getOrderId();
        this.correctionNumber = correctionNumber;
        this.reason = reason;
        this.originalNet = originalNet;
        this.originalVat = originalVat;
        this.originalGross = originalGross;
        this.correctionNet = correctionNet;
        this.correctionVat = correctionVat;
        this.correctionGross = correctionGross;
        this.finalNet = finalNet;
        this.finalVat = finalVat;
        this.finalGross = finalGross;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Invoice getInvoice() { return invoice; }
    public Long getOrderId() { return orderId; }
    public String getCorrectionNumber() { return correctionNumber; }
    public String getReason() { return reason; }
    public BigDecimal getOriginalNet() { return originalNet; }
    public BigDecimal getOriginalVat() { return originalVat; }
    public BigDecimal getOriginalGross() { return originalGross; }
    public BigDecimal getCorrectionNet() { return correctionNet; }
    public BigDecimal getCorrectionVat() { return correctionVat; }
    public BigDecimal getCorrectionGross() { return correctionGross; }
    public BigDecimal getFinalNet() { return finalNet; }
    public BigDecimal getFinalVat() { return finalVat; }
    public BigDecimal getFinalGross() { return finalGross; }
    public Instant getCreatedAt() { return createdAt; }
}
