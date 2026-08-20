package org.demo.customerservice.auth;

public record AuthLoginResult(AuthSession session, MfaLoginChallenge challenge) {
    public static AuthLoginResult authenticated(AuthSession session) {
        return new AuthLoginResult(session, null);
    }

    public static AuthLoginResult mfaRequired(MfaLoginChallenge challenge) {
        return new AuthLoginResult(null, challenge);
    }

    public boolean requiresMfa() {
        return challenge != null;
    }
}
