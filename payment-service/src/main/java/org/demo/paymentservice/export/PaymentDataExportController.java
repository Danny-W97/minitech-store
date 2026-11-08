package org.demo.paymentservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments/me/data-export/orders")
@SecurityRequirement(name="bearerAuth")
public class PaymentDataExportController {
    private final PaymentDataExportService service;
    public PaymentDataExportController(PaymentDataExportService service){this.service=service;}
    @GetMapping(value="/{orderId}",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download stored payment and refund data for your own order",
            description="Customer owner verified through Order on every request. Partial export; no provider calls or reconciliation.")
    public ResponseEntity<PaymentDataExport> export(@PathVariable long orderId) {
        var actor=Access.actor();Access.require("CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"payment-data.json\"")
                .header("X-Content-Type-Options","nosniff").body(service.export(orderId));
    }
}
