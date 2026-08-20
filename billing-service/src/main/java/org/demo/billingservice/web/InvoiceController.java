package org.demo.billingservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.demo.billingservice.billing.BillingService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/invoices") @Tag(name = "Billing", description = "Invoices and billing data")
public class InvoiceController {
    private final BillingService service;
    public InvoiceController(BillingService service) { this.service = service; }
    @PostMapping @Operation(summary = "Issue an invoice")
    public ResponseEntity<InvoiceResponse> issue(@Valid @RequestBody IssueInvoiceRequest request) { return ResponseEntity.status(HttpStatus.CREATED).body(service.issue(request)); }
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
        String filename = (invoice.invoiceNumber() == null ? "faktura" : invoice.invoiceNumber()) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }
}
