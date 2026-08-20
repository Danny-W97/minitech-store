package org.demo.customerservice.mfa;

import java.util.List;

public final class MfaResponses {
    private MfaResponses() {
    }

    public record Status(boolean totpEnabled, boolean webAuthnAvailable, int recoveryCodesRemaining) {
    }

    public record TotpEnrollment(String secret, String otpauthUri, String qrCodeDataUri, long expiresIn) {
    }

    public record RecoveryCodes(List<String> codes) {
    }

    public record WebAuthnStatus(boolean available, int registeredCredentials) {
    }
}
