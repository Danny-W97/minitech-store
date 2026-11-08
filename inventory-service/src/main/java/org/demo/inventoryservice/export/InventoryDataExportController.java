package org.demo.inventoryservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory/me/data-export/orders/{orderId}")
@SecurityRequirement(name="bearerAuth")
public class InventoryDataExportController {
    private final InventoryDataExportService service;
    public InventoryDataExportController(InventoryDataExportService service){this.service=service;}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download stored reservation data for your own order",description="Order ownership checked on every request. No expiry, stock mutation or reconstruction; partial export.")
    public ResponseEntity<InventoryDataExport> export(@PathVariable long orderId){allowed();return response("inventory-reservation-data.json",service.export(orderId));}
    @GetMapping(value="/items",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a bounded page of reservation items for your own order",description="Current Order owner required, including empty or exhausted pages. Does not return global stock or stock operation keys.")
    public ResponseEntity<InventoryItemDataExportPage> items(@PathVariable long orderId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size){allowed();return response("inventory-reservation-items-page.json",service.items(orderId,cursor,size));}
    private static void allowed(){var actor=Access.actor();Access.require("CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);}
    private static <T> ResponseEntity<T> response(String filename,T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"").header("X-Content-Type-Options","nosniff").body(body);}
}
