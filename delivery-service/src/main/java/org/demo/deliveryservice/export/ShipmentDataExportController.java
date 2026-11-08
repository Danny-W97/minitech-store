package org.demo.deliveryservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/deliveries/me/data-export/orders")
@SecurityRequirement(name="bearerAuth")
public class ShipmentDataExportController {
    private final ShipmentDataExportService service;
    public ShipmentDataExportController(ShipmentDataExportService service) { this.service = service; }

    @GetMapping(value="/{orderId}", produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download shipment data for your own order",
            description="Owner verified through Order on every request. Partial export; no cross-service snapshot.")
    public ResponseEntity<ShipmentDataExport> export(@PathVariable long orderId) {
        var actor = Access.actor();
        Access.require("CUSTOMER".equals(actor.role()) && actor.customerId() != null && actor.customerId() > 0);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shipment-data.json\"")
                .header("X-Content-Type-Options", "nosniff").body(service.export(orderId));
    }
}
