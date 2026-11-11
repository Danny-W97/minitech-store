package org.demo.orderservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders/me/data-export")
@SecurityRequirement(name="bearerAuth")
public class OrderDataExportController {
    private final OrderDataExportService service;
    public OrderDataExportController(OrderDataExportService service){this.service=service;}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a page of your own order headers",description="Items use a separate bounded endpoint; partial account export, no shared snapshot.")
    public ResponseEntity<OrderDataExportPage> orders(@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        return response("orders-data-page.json",service.orders(owner(),cursor,size));
    }
    @GetMapping(value="/{orderId}/items",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a page of items from your own order")
    public ResponseEntity<OrderItemDataExportPage> items(@PathVariable long orderId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        return response("order-items-data-page.json",service.items(owner(),orderId,cursor,size));
    }
    private long owner() {
        var actor=Access.actor();Access.require("CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);
        return actor.customerId();
    }
    private <T> ResponseEntity<T> response(String filename,T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
                .header("X-Content-Type-Options","nosniff").body(body);
    }
}
