package org.demo.customerservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class MfaRequests {
    private MfaRequests() {
    }

    public record Code(
            @NotBlank
            @Pattern(regexp = "\\d{6}", message = "Kod TOTP musi zawierać dokładnie 6 cyfr.")
            String code
    ) {
    }

    public record LoginCode(@NotBlank String challengeId, @NotBlank String code) {
    }

    public record WebAuthnLogin(
            @NotBlank String challengeId,
            @NotBlank String id,
            @NotBlank String rawId,
            @NotBlank String type,
            @jakarta.validation.Valid WebAuthnResponse response
    ) {
    }

    public record WebAuthnResponse(
            @NotBlank String clientDataJSON,
            @NotBlank String authenticatorData,
            @NotBlank String signature,
            String userHandle
    ) {
    }

    public record WebAuthnLabel(String label) {
    }
}
