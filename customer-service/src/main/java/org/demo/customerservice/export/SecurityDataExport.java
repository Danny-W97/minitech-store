package org.demo.customerservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record SecurityDataExport(int schemaVersion,String scope,boolean complete,Instant generatedAt,Totp totp,List<Omission> omissions) {
    @Schema(name="SecurityExportTotp")
    public record Totp(boolean enabled,Instant createdAt,Instant updatedAt,Instant enrollmentExpiresAt) {}
    @Schema(name="SecurityExportOmission")
    public record Omission(String area,String reason) {}
    @Schema(name="SecurityExportAuditEvent")
    public record Event(long eventId,String eventType,String outcome,Instant occurredAt,String subjectProof) {}
    @Schema(name="SecurityExportPasskey")
    public record Passkey(long exportId,Instant createdAt,Instant lastUsedAt,boolean userVerificationInitialized,boolean backupEligible,boolean backupState) {}
    @Schema(name="SecurityExportRecoveryCodeMetadata")
    public record Recovery(long recordId,Instant createdAt,Instant usedAt) {}
}
