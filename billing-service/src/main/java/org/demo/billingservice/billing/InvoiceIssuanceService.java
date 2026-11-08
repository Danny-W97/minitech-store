package org.demo.billingservice.billing;

import org.demo.billingservice.messaging.InvoiceEventPublisher;
import org.demo.billingservice.web.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.Optional;

/** Writes a previously verified order snapshot in a short local transaction. */
@Service
public class InvoiceIssuanceService {
    private final InvoiceRepository repository;
    private final InvoiceSequenceGenerator sequenceGenerator;
    private final InvoiceEventPublisher publisher;

    public InvoiceIssuanceService(InvoiceRepository repository, InvoiceSequenceGenerator sequenceGenerator,
                                 InvoiceEventPublisher publisher) {
        this.repository = repository;
        this.sequenceGenerator = sequenceGenerator;
        this.publisher = publisher;
    }

    @Transactional
    public InvoiceResponse issue(IssueInvoiceRequest verified) {
        var existing = repository.findByOrderId(verified.orderId());
        // Retrying never alters an issued document, including legacy documents without line items.
        if (existing.isPresent()) return InvoiceResponse.from(existing.get());
        BigDecimal gross = verified.grossAmount();
        BigDecimal net = gross.divide(new BigDecimal("1.23"), 2, RoundingMode.HALF_UP);
        BigDecimal vat = gross.subtract(net);
        var data = verified.billingData();
        var invoice = repository.save(new Invoice(verified.orderId(), verified.customerId(), verified.customerEmail(),
                data.company() ? BillingKind.COMPANY : BillingKind.PERSON, data.buyerName(), data.companyName(), data.taxId(),
                data.street(), data.postalCode(), data.city(), data.country(), net, vat, gross,
                verified.deliveryCost(), verified.discountAmount(), verified.currency()));
        verified.items().forEach(item -> invoice.addItem(item.productId(), item.sku(), item.productName(), item.quantity(),
                item.unitPrice(), item.totalPrice()));
        invoice.assignNumber(sequenceGenerator.nextInvoiceNumber(Year.now().getValue()));
        Invoice saved = repository.save(invoice);
        publisher.publishInvoiceIssued(saved);
        return InvoiceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Optional<InvoiceResponse> findExisting(Long orderId) {
        return repository.findByOrderId(orderId).map(InvoiceResponse::from);
    }
}
