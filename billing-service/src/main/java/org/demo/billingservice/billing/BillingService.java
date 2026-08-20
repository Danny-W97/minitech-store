package org.demo.billingservice.billing;

import org.demo.billingservice.web.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.time.Year;
import java.util.List;

@Service
public class BillingService {
    private static final BigDecimal VAT_RATE = new BigDecimal("0.23");
    private final InvoiceRepository repository;
    private final InvoicePdfService pdfService;
    public BillingService(InvoiceRepository repository, InvoicePdfService pdfService) {
        this.repository = repository;
        this.pdfService = pdfService;
    }
    @Transactional
    public InvoiceResponse issue(IssueInvoiceRequest request) {
        return repository.findByOrderId(request.orderId()).map(existing -> {
            if (existing.getItems().isEmpty()) {
                existing.backfillOrderBreakdown(request.deliveryCost(), request.discountAmount());
                appendItems(existing, request.items());
            }
            return InvoiceResponse.from(existing);
        }).orElseGet(() -> {
            BigDecimal gross = request.grossAmount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.divide(BigDecimal.ONE.add(VAT_RATE), 2, RoundingMode.HALF_UP);
            BigDecimal vat = gross.subtract(net).setScale(2, RoundingMode.HALF_UP);
            BillingData data = request.billingData();
            Invoice invoice = repository.save(new Invoice(request.orderId(), request.customerId(), request.customerEmail(),
                    data.company() ? BillingKind.COMPANY : BillingKind.PERSON, data.buyerName(), data.companyName(), data.taxId(),
                    data.street(), data.postalCode(), data.city(), data.country(), net, vat, gross,
                    request.deliveryCost(), request.discountAmount(),
                    request.currency() == null || request.currency().isBlank() ? "PLN" : request.currency().toUpperCase()));
            appendItems(invoice, request.items());
            invoice.assignNumber("FV/" + Year.now() + "/" + String.format("%06d", invoice.getId()));
            return InvoiceResponse.from(invoice);
        });
    }
    private void appendItems(Invoice invoice, List<InvoiceItemRequest> items) {
        items.forEach(item -> invoice.addItem(item.productId(), item.sku(), item.productName(), item.quantity(),
                item.unitPrice().setScale(2, RoundingMode.HALF_UP), item.totalPrice().setScale(2, RoundingMode.HALF_UP)));
    }
    @Transactional(readOnly = true) public InvoiceResponse byOrder(Long orderId) { return repository.findByOrderId(orderId).map(InvoiceResponse::from).orElseThrow(() -> notFound("zamówienia " + orderId)); }
    @Transactional(readOnly = true) public InvoiceResponse get(Long id) { return repository.findById(id).map(InvoiceResponse::from).orElseThrow(() -> notFound("faktury " + id)); }
    @Transactional(readOnly = true) public byte[] pdf(Long id) { return repository.findById(id).map(pdfService::generate).orElseThrow(() -> notFound("faktury " + id)); }
    @Transactional(readOnly = true) public List<InvoiceResponse> customer(Long customerId) { return repository.findAllByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(InvoiceResponse::from).toList(); }
    private ResponseStatusException notFound(String value) { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono " + value + "."); }
}
