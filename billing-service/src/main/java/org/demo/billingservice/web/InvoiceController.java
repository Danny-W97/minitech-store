package org.demo.billingservice.web;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.OrderAccess;
import org.demo.platformsecurity.ListWindow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.demo.billingservice.billing.BillingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController @RequestMapping("/invoices") @Tag(name = "Billing", description = "Invoices and billing data")
public class InvoiceController {
    private final OrderAccess orderAccess;
    private final BillingService service;
    public InvoiceController(BillingService service, OrderAccess orderAccess) { this.service = service; this.orderAccess = orderAccess; }

    @GetMapping
    @Operation(summary = "List all invoices with optional search filter and pagination")
    public ResponseEntity<List<InvoiceResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (page == null) return service.page(null, search, new ListWindow(cursor, size)).response();
        new ListWindow(cursor, size);
        if (page < 0) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative");
        List<InvoiceResponse> list = service.list(search, page, size);
        long total = service.count(search);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .body(list);
    }

    @PostMapping @Operation(summary = "Issue an invoice")
    public ResponseEntity<InvoiceResponse> issue(@Valid @RequestBody IssueInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issue(request));
    }

    @PostMapping("/{id}/corrections")
    @Operation(summary = "Issue a correction invoice (credit note)")
    public ResponseEntity<InvoiceCorrectionResponse> issueCorrection(
            @PathVariable @Positive Long id,
            @Valid @RequestBody IssueCorrectionRequest request
    ) {
        var actor = Access.actor();
        Access.require(actor.operator() || (actor.internal() && "returns-service".equals(actor.service())));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueCorrection(id, request));
    }

    @GetMapping("/{id}/corrections")
    @Operation(summary = "Get all corrections for a specific invoice")
    public ResponseEntity<List<InvoiceCorrectionResponse>> getCorrections(@PathVariable @Positive Long id, @RequestParam(defaultValue = "0") long cursor, @RequestParam(defaultValue = "50") int size) {
        orderAccess.require(service.get(id).orderId());
        return service.corrections(id, new ListWindow(cursor, size)).response();
    }
    @GetMapping("/{id}/correction-balance")
    public InvoiceBalanceResponse correctionBalance(@PathVariable @Positive Long id) {
        var actor = Access.actor();
        Access.require(actor.operator() || (actor.internal() && "returns-service".equals(actor.service())));
        return service.correctionBalance(id);
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<java.util.Map<String,String>> requestRejected(org.springframework.web.server.ResponseStatusException error) {
        String reason=error.getReason()==null ? "Invoice request rejected" : error.getReason();
        String code=reason.startsWith("CORRECTION_") ? reason : "INVOICE_REQUEST_REJECTED";
        return ResponseEntity.status(error.getStatusCode()).body(java.util.Map.of("error",reason,"code",code));
    }

    @GetMapping("/corrections")
    @Operation(summary = "Get all invoice corrections")
    public ResponseEntity<List<InvoiceCorrectionResponse>> getAllCorrections(@RequestParam(defaultValue = "0") long cursor, @RequestParam(defaultValue = "50") int size) {
        return service.corrections(null, new ListWindow(cursor, size)).response();
    }

    @GetMapping("/corrections/summary")
    public List<org.demo.billingservice.billing.InvoiceCorrectionRepository.CorrectionTotals> correctionTotals(@RequestParam List<Long> invoiceIds) {
        return service.correctionTotals(invoiceIds);
    }

    @GetMapping(value = "/corrections/{correctionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Download correction invoice PDF")
    public ResponseEntity<byte[]> correctionPdf(@PathVariable @Positive Long correctionId) {
        InvoiceCorrectionResponse correction = service.getCorrection(correctionId);
        orderAccess.require(correction.orderId());
        String filename = (correction.correctionNumber() == null ? "korekta" : correction.correctionNumber().replace('/', '_')) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(service.pdfCorrection(correctionId));
    }

    @GetMapping(value = "/export/zip", produces = "application/zip")
    @Operation(summary = "Export invoices as a ZIP archive of PDFs")
    public void exportZip(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            jakarta.servlet.http.HttpServletResponse response
    ) throws java.io.IOException {
        // Validate scope and reserve capacity before committing an archive response.
        try (var session = service.prepareExport(from, to)) {
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"faktury.zip\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            session.writeTo(response.getOutputStream());
        }
    }

    @GetMapping("/order/{orderId}") public InvoiceResponse byOrder(@PathVariable @Positive Long orderId) { orderAccess.require(orderId); return service.byOrder(orderId); }
    @GetMapping(value = "/order/{orderId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> orderPdf(@PathVariable @Positive Long orderId) {
        orderAccess.require(orderId);
        InvoiceResponse invoice = service.byOrder(orderId);
        return pdfResponse(invoice, service.pdf(invoice.id()));
    }
    @GetMapping("/customer/{customerId}") public ResponseEntity<List<InvoiceResponse>> customer(@PathVariable @Positive Long customerId, @RequestParam(defaultValue = "0") long cursor, @RequestParam(defaultValue = "50") int size) { Access.customer(customerId); return service.page(customerId, null, new ListWindow(cursor, size)).response(); }
    @GetMapping("/{id}") public InvoiceResponse get(@PathVariable @Positive Long id) { InvoiceResponse invoice = service.get(id); orderAccess.require(invoice.orderId()); return invoice; }
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable @Positive Long id) {
        InvoiceResponse invoice = service.get(id);
        orderAccess.require(invoice.orderId());
        return pdfResponse(invoice, service.pdf(id));
    }

    private ResponseEntity<byte[]> pdfResponse(InvoiceResponse invoice, byte[] content) {
        String rawNumber = invoice.invoiceNumber() == null ? "faktura" : invoice.invoiceNumber();
        String filename = rawNumber.replace('/', '_') + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }
}
