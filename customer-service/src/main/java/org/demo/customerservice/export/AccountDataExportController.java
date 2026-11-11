package org.demo.customerservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.customerservice.auth.CustomerPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountDataExportController {
    private final AccountDataExportService service;

    public AccountDataExportController(AccountDataExportService service) {
        this.service = service;
    }

    @GetMapping(value = "/customers/me/data-export", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Download own profile and security summary",
            description = "Partial account export. Security metadata/audit have separate endpoints with manual review for free text; other services/copies remain pending. Secrets excluded.")
    public ResponseEntity<AccountDataExport> export(@AuthenticationPrincipal CustomerPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customer-profile-export.json\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.export(principal));
    }
}
