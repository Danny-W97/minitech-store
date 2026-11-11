package org.demo.billingservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.billingservice.billing.BillingService;
import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/invoices/me/data-export/orders/{orderId}")
@SecurityRequirement(name="bearerAuth")
public class InvoiceDataExportController {
    private final InvoiceDataExportService service;
    private final BillingService billing;
    public InvoiceDataExportController(InvoiceDataExportService service,BillingService billing){this.service=service;this.billing=billing;}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download stored invoice data for your own order",description="Order is the source of ownership, regardless of the local invoice customer ID. Partial export.")
    public ResponseEntity<InvoiceDataExport> invoice(@PathVariable long orderId) {
        owner();return response("invoice-data.json",MediaType.APPLICATION_JSON,service.invoice(orderId));
    }
    @GetMapping(value="/items",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a page of your invoice items",description="Use nextCursor unchanged; item cursor refers to positionNo.")
    public ResponseEntity<InvoiceItemDataExportPage> items(@PathVariable long orderId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        owner();return response("invoice-items-data-page.json",MediaType.APPLICATION_JSON,service.items(orderId,cursor,size));
    }
    @GetMapping(value="/corrections",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a page of your invoice corrections",description="Stored amount snapshots and legacy balance model preserved; no recalculation.")
    public ResponseEntity<InvoiceCorrectionDataExportPage> corrections(@PathVariable long orderId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        owner();return response("invoice-corrections-data-page.json",MediaType.APPLICATION_JSON,service.corrections(orderId,cursor,size));
    }
    @GetMapping(value="/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary="Download the PDF rendering of your existing invoice")
    public ResponseEntity<byte[]> pdf(@PathVariable long orderId) {
        owner();long id=service.invoiceId(orderId);return response("invoice.pdf",MediaType.APPLICATION_PDF,billing.pdf(id));
    }
    @GetMapping(value="/corrections/{correctionId}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary="Download the PDF rendering of your existing correction",description="Order ownership and correction/invoice linkage checked before the existing PDF cache is accessed.")
    public ResponseEntity<byte[]> correctionPdf(@PathVariable long orderId,@PathVariable long correctionId) {
        owner();long id=service.correctionId(orderId,correctionId);return response("invoice-correction.pdf",MediaType.APPLICATION_PDF,billing.pdfCorrection(id));
    }
    private void owner(){var actor=Access.actor();Access.require("CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);}
    private <T> ResponseEntity<T> response(String filename,MediaType type,T body) {
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
                .header("X-Content-Type-Options","nosniff").body(body);
    }
}
