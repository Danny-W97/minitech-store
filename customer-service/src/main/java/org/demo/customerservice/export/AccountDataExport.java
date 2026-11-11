package org.demo.customerservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Deliberate allowlist: never serialize a persistence entity into an account download. */
public record AccountDataExport(
        int schemaVersion,
        String scope,
        boolean complete,
        Instant generatedAt,
        Profile profile,
        SecuritySummary security,
        List<Omission> omissions
) {
    @Schema(name = "AccountExportProfile")
    public record Profile(Long id, String email, String firstName, String lastName,
                          String street, String postalCode, String city, String country,
                          String phoneNumber, boolean enabled, Instant createdAt, Instant updatedAt) {}

    @Schema(name = "AccountExportSecuritySummary")
    public record SecuritySummary(boolean totpEnabled, long passkeyCount, long unusedRecoveryCodeCount) {}

    @Schema(name = "AccountExportOmission")
    public record Omission(String scope, String status) {}
}
