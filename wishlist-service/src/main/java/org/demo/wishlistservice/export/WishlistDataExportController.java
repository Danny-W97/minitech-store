package org.demo.wishlistservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WishlistDataExportController {
    private final WishlistDataExportService service;

    public WishlistDataExportController(WishlistDataExportService service) { this.service = service; }

    @GetMapping(value = "/wishlists/me/data-export", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Download a page of your saved products",
            description = "Partial account export; keyset pages do not form a snapshot across requests.")
    public ResponseEntity<WishlistDataExportPage> export(@RequestParam(defaultValue = "0") long cursor,
                                                         @RequestParam(defaultValue = "50") int size) {
        var actor = Access.actor();
        Access.require("CUSTOMER".equals(actor.role()) && actor.customerId() != null && actor.customerId() > 0);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wishlist-data-page.json\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.export(actor.customerId(), cursor, size));
    }
}
