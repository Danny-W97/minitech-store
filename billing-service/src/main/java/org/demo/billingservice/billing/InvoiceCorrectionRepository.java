package org.demo.billingservice.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InvoiceCorrectionRepository extends JpaRepository<InvoiceCorrection, Long> {
    List<InvoiceCorrection> findAllByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);
    List<InvoiceCorrection> findAllByOrderByCreatedAtDesc();
}
