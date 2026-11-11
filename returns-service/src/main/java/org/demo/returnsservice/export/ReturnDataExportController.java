package org.demo.returnsservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/returns/me/data-export")
@SecurityRequirement(name="bearerAuth")
public class ReturnDataExportController {
    private final ReturnDataExportService service;
    public ReturnDataExportController(ReturnDataExportService service){this.service=service;}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a page of your own returns and complaints",description="Partial export; items and safe status events use separate bounded endpoints.")
    public ResponseEntity<ReturnDataExportPage> returns(@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        return response("returns-data-page.json",service.returns(owner(),cursor,size));
    }
    @GetMapping(value="/{returnId}/items",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a page of items in your own return",description="Use nextCursor unchanged; item cursor refers to orderItemId.")
    public ResponseEntity<ReturnItemDataExportPage> items(@PathVariable long returnId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        return response("return-items-data-page.json",service.items(owner(),returnId,cursor,size));
    }
    @GetMapping(value="/{returnId}/events",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a page of status events in your own return",description="Operator notes, identities and workflow metadata excluded. LEGACY_SNAPSHOT is a migration snapshot, not an original event time.")
    public ResponseEntity<ReturnEventDataExportPage> events(@PathVariable long returnId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        return response("return-events-data-page.json",service.events(owner(),returnId,cursor,size));
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
