package org.demo.billingservice.billing;

import org.demo.billingservice.messaging.InvoiceEventPublisher;
import org.demo.billingservice.web.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.*;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class BillingService {
    private static final BigDecimal VAT_RATE = new BigDecimal("0.23");
    private final InvoiceRepository repository;
    private final InvoiceCorrectionRepository correctionRepository;
    private final InvoicePdfService pdfService;
    private final InvoiceSequenceGenerator sequenceGenerator;
    private final Optional<InvoiceEventPublisher> eventPublisher;
    private final ConcurrentMap<Long, byte[]> pdfCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, byte[]> correctionPdfCache = new ConcurrentHashMap<>();

    public BillingService(InvoiceRepository repository,
                          InvoiceCorrectionRepository correctionRepository,
                          InvoicePdfService pdfService,
                          InvoiceSequenceGenerator sequenceGenerator,
                          @Autowired(required = false) InvoiceEventPublisher eventPublisher) {
        this.repository = repository;
        this.correctionRepository = correctionRepository;
        this.pdfService = pdfService;
        this.sequenceGenerator = sequenceGenerator;
        this.eventPublisher = Optional.ofNullable(eventPublisher);
    }

    @Transactional
    public InvoiceResponse issue(IssueInvoiceRequest request) {
        return repository.findByOrderId(request.orderId()).map(existing -> {
            if (existing.getItems().isEmpty()) {
                existing.backfillOrderBreakdown(request.deliveryCost(), request.discountAmount());
                appendItems(existing, request.items());
                pdfCache.remove(existing.getId());
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
            invoice.assignNumber(sequenceGenerator.nextInvoiceNumber(Year.now().getValue()));
            Invoice saved = repository.save(invoice);
            eventPublisher.ifPresent(p -> p.publishInvoiceIssued(saved));
            return InvoiceResponse.from(saved);
        });
    }

    private void appendItems(Invoice invoice, List<InvoiceItemRequest> items) {
        items.forEach(item -> invoice.addItem(item.productId(), item.sku(), item.productName(), item.quantity(),
                item.unitPrice().setScale(2, RoundingMode.HALF_UP), item.totalPrice().setScale(2, RoundingMode.HALF_UP)));
    }

    @Transactional
    public InvoiceCorrectionResponse issueCorrection(Long invoiceId, IssueCorrectionRequest request) {
        Invoice invoice = repository.findById(invoiceId)
                .orElseThrow(() -> notFound("faktury " + invoiceId));

        BigDecimal corrGross = request.correctionGrossAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal corrNet = corrGross.divide(BigDecimal.ONE.add(VAT_RATE), 2, RoundingMode.HALF_UP);
        BigDecimal corrVat = corrGross.subtract(corrNet).setScale(2, RoundingMode.HALF_UP);

        BigDecimal finalGross = invoice.getGrossAmount().add(corrGross).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalNet = invoice.getNetAmount().add(corrNet).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalVat = invoice.getVatAmount().add(corrVat).setScale(2, RoundingMode.HALF_UP);

        String correctionNumber = sequenceGenerator.nextCorrectionNumber(Year.now().getValue());
        InvoiceCorrection correction = new InvoiceCorrection(
                invoice,
                correctionNumber,
                request.reason().trim(),
                invoice.getNetAmount(),
                invoice.getVatAmount(),
                invoice.getGrossAmount(),
                corrNet,
                corrVat,
                corrGross,
                finalNet,
                finalVat,
                finalGross
        );
        correction = correctionRepository.save(correction);
        InvoiceCorrection finalCorrection = correction;
        eventPublisher.ifPresent(p -> p.publishInvoiceCorrected(finalCorrection));
        return InvoiceCorrectionResponse.from(correction);
    }

    @Transactional(readOnly = true)
    public List<InvoiceCorrectionResponse> getCorrections(Long invoiceId) {
        return correctionRepository.findAllByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
                .map(InvoiceCorrectionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<InvoiceCorrectionResponse> getAllCorrections() {
        return correctionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(InvoiceCorrectionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceCorrectionResponse getCorrection(Long correctionId) {
        return correctionRepository.findById(correctionId)
                .map(InvoiceCorrectionResponse::from)
                .orElseThrow(() -> notFound("korekty " + correctionId));
    }

    @Transactional(readOnly = true)
    public byte[] pdfCorrection(Long correctionId) {
        byte[] cached = correctionPdfCache.get(correctionId);
        if (cached != null) {
            return cached;
        }
        InvoiceCorrection correction = correctionRepository.findById(correctionId)
                .orElseThrow(() -> notFound("korekty " + correctionId));
        byte[] generated = pdfService.generateCorrection(correction, correction.getInvoice());
        correctionPdfCache.put(correctionId, generated);
        return generated;
    }

    @Transactional(readOnly = true) public InvoiceResponse byOrder(Long orderId) { return repository.findByOrderId(orderId).map(InvoiceResponse::from).orElseThrow(() -> notFound("zamówienia " + orderId)); }
    @Transactional(readOnly = true) public InvoiceResponse get(Long id) { return repository.findById(id).map(InvoiceResponse::from).orElseThrow(() -> notFound("faktury " + id)); }
    @Transactional(readOnly = true)
    public byte[] pdf(Long id) {
        byte[] cached = pdfCache.get(id);
        if (cached != null) {
            return cached;
        }
        byte[] generated = repository.findById(id).map(pdfService::generate).orElseThrow(() -> notFound("faktury " + id));
        pdfCache.put(id, generated);
        return generated;
    }
    @Transactional(readOnly = true) public List<InvoiceResponse> customer(Long customerId) { return repository.findAllByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(InvoiceResponse::from).toList(); }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> list(String search, int page, int size) {
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 200));
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (search != null && !search.isBlank()) {
            return repository.searchInvoices(search.trim(), pageable).stream().map(InvoiceResponse::from).toList();
        }
        return repository.findAllByOrderByCreatedAtDesc(pageable).stream().map(InvoiceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long count(String search) {
        if (search != null && !search.isBlank()) {
            return repository.countSearchInvoices(search.trim());
        }
        return repository.count();
    }

    @Transactional(readOnly = true)
    public void exportZip(Instant from, Instant to, OutputStream outputStream) throws IOException {
        List<Invoice> invoices = repository.findAllByOrderByCreatedAtDesc();
        if (from != null) {
            invoices = invoices.stream().filter(i -> !i.getCreatedAt().isBefore(from)).toList();
        }
        if (to != null) {
            invoices = invoices.stream().filter(i -> !i.getCreatedAt().isAfter(to)).toList();
        }

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (Invoice inv : invoices) {
                byte[] pdfBytes = pdf(inv.getId());
                String filename = (inv.getInvoiceNumber() != null ? inv.getInvoiceNumber().replace('/', '_') : "FV_" + inv.getId()) + ".pdf";
                ZipEntry entry = new ZipEntry(filename);
                zos.putNextEntry(entry);
                zos.write(pdfBytes);
                zos.closeEntry();
            }
            zos.finish();
        }
    }

    public void clearPdfCache() {
        pdfCache.clear();
        correctionPdfCache.clear();
    }

    private ResponseStatusException notFound(String value) { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono " + value + "."); }
}


