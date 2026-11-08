package org.demo.billingservice.billing;

import org.demo.billingservice.messaging.InvoiceEventPublisher;
import org.demo.platformsecurity.ListWindow;
import org.demo.billingservice.web.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
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

@Service
public class BillingService {
    private static final BigDecimal VAT_RATE = new BigDecimal("0.23");
    private final InvoiceOrderVerifier orderVerifier;
    private final InvoiceIssuanceService issuance;
    private final InvoiceRepository repository;
    private final InvoiceCorrectionRepository correctionRepository;
    private final InvoicePdfService pdfService;
    private final InvoiceSequenceGenerator sequenceGenerator;
    private final InvoiceEventPublisher eventPublisher;
    private final PdfDocumentCache pdfCache;
    private final InvoiceExportService exports;
    @Autowired private jakarta.validation.Validator validator;

    public BillingService(InvoiceOrderVerifier orderVerifier, InvoiceIssuanceService issuance, InvoiceRepository repository,
                          InvoiceCorrectionRepository correctionRepository,
                          InvoicePdfService pdfService,
                          InvoiceSequenceGenerator sequenceGenerator,
                          InvoiceEventPublisher eventPublisher,
                          PdfDocumentCache pdfCache, InvoiceExportService exports) {
        this.orderVerifier = orderVerifier;
        this.issuance = issuance;
        this.repository = repository;
        this.correctionRepository = correctionRepository;
        this.pdfService = pdfService;
        this.sequenceGenerator = sequenceGenerator;
        this.eventPublisher = eventPublisher;
        this.pdfCache = pdfCache;
        this.exports = exports;
    }

    public InvoiceResponse issue(IssueInvoiceRequest request) {
        // Verify over HTTP before opening the local invoice transaction.
        var verified = orderVerifier.verify(request);
        try {
            return issuance.issue(verified);
        } catch (DataIntegrityViolationException conflict) {
            // Another instance may have committed the same order's invoice meanwhile.
            // Read only after the failed insert transaction has rolled back.
            return issuance.findExisting(verified.orderId()).orElseThrow(() -> conflict);
        }
    }

    @Transactional
    public InvoiceCorrectionResponse issueCorrection(Long invoiceId, IssueCorrectionRequest request) {
        if(request==null || !validator.validate(request).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid correction request");
        Invoice invoice = repository.lockId(invoiceId)
                .orElseThrow(() -> notFound("faktury " + invoiceId));
        var previous=correctionRepository.findByInvoiceIdAndIdempotencyKey(invoiceId,request.idempotencyKey()).orElse(null);
        if(previous!=null) {
            if(!previous.getReason().equals(request.reason().trim()) || previous.getCorrectionGross().compareTo(request.correctionGrossAmount())!=0
                    || previous.getLedgerVersion()-1!=request.expectedVersion())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"CORRECTION_KEY_REUSED");
            return InvoiceCorrectionResponse.from(previous);
        }
        var before=balance(invoice);
        if(before.version()!=request.expectedVersion()) throw new ResponseStatusException(HttpStatus.CONFLICT,"CORRECTION_BALANCE_CHANGED");
        BigDecimal corrGross = request.correctionGrossAmount().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal finalGross = before.grossAmount().add(corrGross);
        if(corrGross.signum()==0 || !validBalance(before) || finalGross.signum()<0 || finalGross.compareTo(MAX_AMOUNT)>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Correction would produce an invalid balance");
        // Round the resulting balance once; derive its delta to avoid cumulative rounding drift.
        BigDecimal finalNet = finalGross.divide(BigDecimal.ONE.add(VAT_RATE),2,RoundingMode.HALF_UP);
        BigDecimal finalVat = finalGross.subtract(finalNet);
        BigDecimal corrNet = finalNet.subtract(before.netAmount());
        BigDecimal corrVat = finalVat.subtract(before.vatAmount());

        String correctionNumber = sequenceGenerator.nextCorrectionNumber(Year.now().getValue());
        InvoiceCorrection correction = new InvoiceCorrection(
                invoice,
                correctionNumber,
                request.reason().trim(),
                before.netAmount(),
                before.vatAmount(),
                before.grossAmount(),
                corrNet,
                corrVat,
                corrGross,
                finalNet,
                finalVat,
                finalGross
        );
        correction.bindLedger(request.idempotencyKey(),Math.addExact(before.version(),1));
        correction = correctionRepository.saveAndFlush(correction);
        InvoiceCorrection finalCorrection = correction;
        eventPublisher.publishInvoiceCorrected(finalCorrection);
        return InvoiceCorrectionResponse.from(correction);
    }
    private static final BigDecimal MAX_AMOUNT=new BigDecimal("99999999999999999.99");
    @Transactional(readOnly=true) public InvoiceBalanceResponse correctionBalance(Long id) {
        return balance(repository.findById(id).orElseThrow(() -> notFound("faktury "+id)));
    }
    private InvoiceBalanceResponse balance(Invoice invoice) {
        var delta=correctionRepository.balance(invoice.getId());
        return new InvoiceBalanceResponse(invoice.getId(),delta.getVersion(),invoice.getNetAmount().add(delta.getNet()),
                invoice.getVatAmount().add(delta.getVat()),invoice.getGrossAmount().add(delta.getGross()),invoice.getCurrency(),delta.getLegacyCount());
    }
    private boolean validBalance(InvoiceBalanceResponse value) {
        return "PLN".equals(value.currency()) && value.netAmount().signum()>=0 && value.vatAmount().signum()>=0
                && value.grossAmount().signum()>=0 && value.grossAmount().compareTo(MAX_AMOUNT)<=0
                && value.netAmount().add(value.vatAmount()).compareTo(value.grossAmount())==0;
    }

    @Transactional(readOnly = true)
    public List<InvoiceCorrectionResponse> getCorrections(Long invoiceId) {
        return corrections(invoiceId, ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public List<InvoiceCorrectionResponse> getAllCorrections() {
        return corrections(null, ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public ListWindow.ListPage<InvoiceCorrectionResponse> corrections(Long invoiceId, ListWindow window) {
        return window.map(correctionRepository.page(window.ceiling(), invoiceId, window.fetch()), InvoiceCorrection::getId, InvoiceCorrectionResponse::from);
    }

    @Transactional(readOnly = true)
    public ListWindow.ListPage<InvoiceResponse> page(Long customerId, String search, ListWindow window) {
        return window.map(repository.page(window.ceiling(), customerId, ListWindow.search(search), window.fetch()), Invoice::getId, InvoiceResponse::from);
    }

    @Transactional(readOnly = true)
    public List<InvoiceCorrectionRepository.CorrectionTotals> correctionTotals(List<Long> ids) {
        if (ids.isEmpty() || ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1 to 200 positive invoice IDs");
        return correctionRepository.totals(ids);
    }

    @Transactional(readOnly = true)
    public InvoiceCorrectionResponse getCorrection(Long correctionId) {
        return correctionRepository.findById(correctionId)
                .map(InvoiceCorrectionResponse::from)
                .orElseThrow(() -> notFound("korekty " + correctionId));
    }

    @Transactional(readOnly = true)
    public byte[] pdfCorrection(Long correctionId) {
        byte[] cached = pdfCache.get(PdfDocumentCache.Kind.CORRECTION, correctionId);
        if (cached != null) {
            return cached;
        }
        InvoiceCorrection correction = correctionRepository.findById(correctionId)
                .orElseThrow(() -> notFound("korekty " + correctionId));
        byte[] generated = pdfService.generateCorrection(correction, correction.getInvoice());
        pdfCache.put(PdfDocumentCache.Kind.CORRECTION, correctionId, generated);
        return generated;
    }

    @Transactional(readOnly = true) public InvoiceResponse byOrder(Long orderId) { return repository.findByOrderId(orderId).map(InvoiceResponse::from).orElseThrow(() -> notFound("zamówienia " + orderId)); }
    @Transactional(readOnly = true) public InvoiceResponse get(Long id) { return repository.findById(id).map(InvoiceResponse::from).orElseThrow(() -> notFound("faktury " + id)); }
    @Transactional(readOnly = true)
    public byte[] pdf(Long id) {
        byte[] cached = pdfCache.get(PdfDocumentCache.Kind.INVOICE, id);
        if (cached != null) {
            return cached;
        }
        byte[] generated = repository.findById(id).map(pdfService::generate).orElseThrow(() -> notFound("faktury " + id));
        pdfCache.put(PdfDocumentCache.Kind.INVOICE, id, generated);
        return generated;
    }
    @Transactional(readOnly = true) public List<InvoiceResponse> customer(Long customerId) { return page(customerId, null, ListWindow.FIRST).items(); }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> list(String search, int page, int size) {
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 200));
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
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

    public void exportZip(Instant from, Instant to, OutputStream outputStream) throws IOException {
        try (var session = prepareExport(from, to)) {
            session.writeTo(outputStream);
        }
    }

    public InvoiceExportService.Session prepareExport(Instant from, Instant to) { return exports.open(from, to); }

    public void clearPdfCache() {
        pdfCache.clear();
    }

    private ResponseStatusException notFound(String value) { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono " + value + "."); }
}


