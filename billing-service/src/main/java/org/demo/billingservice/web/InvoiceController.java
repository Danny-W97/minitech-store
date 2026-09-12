package org.demo.billingservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.demo.billingservice.billing.BillingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.List;

@RestController @RequestMapping("/invoices") @Tag(name = "Billing", description = "Invoices and billing data")
public class InvoiceController {
    private final BillingService service;
    public InvoiceController(BillingService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "List all invoices with optional search filter and pagination")
    public ResponseEntity<List<InvoiceResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
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
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueCorrection(id, request));
    }

    @GetMapping("/{id}/corrections")
    @Operation(summary = "Get all corrections for a specific invoice")
    public ResponseEntity<List<InvoiceCorrectionResponse>> getCorrections(@PathVariable @Positive Long id) {
        return ResponseEntity.ok(service.getCorrections(id));
    }

    @GetMapping("/corrections")
    @Operation(summary = "Get all invoice corrections")
    public ResponseEntity<List<InvoiceCorrectionResponse>> getAllCorrections() {
        return ResponseEntity.ok(service.getAllCorrections());
    }

    @GetMapping(value = "/corrections/{correctionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Download correction invoice PDF")
    public ResponseEntity<byte[]> correctionPdf(@PathVariable @Positive Long correctionId) {
        InvoiceCorrectionResponse correction = service.getCorrection(correctionId);
        String filename = (correction.correctionNumber() == null ? "korekta" : correction.correctionNumber().replace('/', '_')) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(service.pdfCorrection(correctionId));
    }

    @GetMapping(value = "/export/zip", produces = "application/zip")
    @Operation(summary = "Export invoices as a ZIP archive of PDFs")
    public ResponseEntity<StreamingResponseBody> exportZip(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        StreamingResponseBody stream = outputStream -> service.exportZip(from, to, outputStream);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("faktury.zip").build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(stream);
    }

    @GetMapping("/order/{orderId}") public InvoiceResponse byOrder(@PathVariable @Positive Long orderId) { return service.byOrder(orderId); }
    @GetMapping(value = "/order/{orderId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> orderPdf(@PathVariable @Positive Long orderId) {
        InvoiceResponse invoice = service.byOrder(orderId);
        return pdfResponse(invoice, service.pdf(invoice.id()));
    }
    @GetMapping("/customer/{customerId}") public List<InvoiceResponse> customer(@PathVariable @Positive Long customerId) { return service.customer(customerId); }
    @GetMapping("/{id}") public InvoiceResponse get(@PathVariable @Positive Long id) { return service.get(id); }
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable @Positive Long id) {
        InvoiceResponse invoice = service.get(id);
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
