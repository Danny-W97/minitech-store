package org.demo.billingservice.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InvoiceCorrectionRepository extends JpaRepository<InvoiceCorrection, Long> {
    java.util.Optional<InvoiceCorrection> findByInvoiceIdAndIdempotencyKey(Long invoiceId,String key);
    @org.springframework.data.jpa.repository.Query("select count(c) as version, coalesce(sum(c.correctionNet),0) as net, coalesce(sum(c.correctionVat),0) as vat, coalesce(sum(c.correctionGross),0) as gross, coalesce(sum(case when c.ledgerVersion is null then 1 else 0 end),0) as legacyCount from InvoiceCorrection c where c.invoice.id=:id")
    BalanceTotals balance(@org.springframework.data.repository.query.Param("id") Long id);
    interface BalanceTotals { long getVersion(); java.math.BigDecimal getNet(); java.math.BigDecimal getVat(); java.math.BigDecimal getGross(); long getLegacyCount(); }
    @org.springframework.data.jpa.repository.Query("select c.invoice.id as invoiceId, count(c) as correctionCount, sum(c.correctionGross) as grossDelta from InvoiceCorrection c where c.invoice.id in :ids group by c.invoice.id")
    List<CorrectionTotals> totals(@org.springframework.data.repository.query.Param("ids") List<Long> ids);
    interface CorrectionTotals { Long getInvoiceId(); long getCorrectionCount(); java.math.BigDecimal getGrossDelta(); }
    @org.springframework.data.jpa.repository.Query("select c from InvoiceCorrection c join fetch c.invoice where c.id <= :ceiling and (:invoiceId is null or c.invoice.id = :invoiceId) order by c.id desc")
    List<InvoiceCorrection> page(@org.springframework.data.repository.query.Param("ceiling") long ceiling, @org.springframework.data.repository.query.Param("invoiceId") Long invoiceId, org.springframework.data.domain.Pageable pageable);
    List<InvoiceCorrection> findAllByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);
    List<InvoiceCorrection> findAllByOrderByCreatedAtDesc();
}
