package org.demo.customerservice.mfa;

import java.util.List;

public final class WebAuthnResponses {
    private WebAuthnResponses() {
    }

    public record RegistrationOptions(
            String challenge,
            String rpId,
            String rpName,
            User user,
            long timeout,
            List<String> pubKeyCredParams,
            List<String> excludeCredentials,
            String userVerification
    ) {
        public record User(String id, String name, String displayName) {
        }
    }

    public record RequestOptions(
            String challenge,
            long timeout,
            String rpId,
            List<String> allowCredentials,
            String userVerification
    ) {
    }

    public record RegisteredCredential(String credentialId, String label) {
    }
}
