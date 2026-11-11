package org.demo.reviewservice.export;

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
public class ReviewDataExportController {
    private final ReviewDataExportService service;
    public ReviewDataExportController(ReviewDataExportService service) { this.service = service; }

    @GetMapping(value = "/reviews/me/data-export", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Download a page of your own reviews",
            description = "Customer-authored content and publication status only; no moderator notes/order links. Pages are not a shared snapshot.")
    public ResponseEntity<ReviewDataExportPage> export(@RequestParam(defaultValue = "0") long cursor,
                                                       @RequestParam(defaultValue = "50") int size) {
        var actor = Access.actor();
        Access.require("CUSTOMER".equals(actor.role()) && actor.customerId() != null && actor.customerId() > 0);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reviews-data-page.json\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.export(actor.customerId(), cursor, size));
    }
}
