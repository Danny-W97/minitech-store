package org.demo.platformsecurity.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Integrity of the reviewed D02 plan and its parameters only.
 * This is not complete preservation-policy approval: D01/P09 remain unresolved.
 * A matching digest must never confirm PRESERVATION_POLICY_REVIEWED.
 */
public final class CommerceRetentionPreservationReview {
    public static final String RESOURCE = "/privacy/commerce-retention-preservation-review.json";
    public static final String SHA256 = "ff1c103e825313818c0a6c24e08697fe0aa961b3262844d9ff98634fb787b05c";
    private static final int LIMIT = 65536;

    private CommerceRetentionPreservationReview() {}

    public static String verifiedDigest() {
        try (var input = CommerceRetentionPreservationReview.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw rejected();
            return verify(input.readNBytes(LIMIT + 1));
        } catch (IOException failure) {
            throw rejected();
        }
    }

    static String verify(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > LIMIT) throw rejected();
        byte[] normalized = new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
            if (!SHA256.equals(actual)) throw rejected();
            return actual;
        } catch (NoSuchAlgorithmException impossible) {
            throw rejected();
        }
    }

    private static IllegalStateException rejected() {
        return new IllegalStateException("RETENTION_PRESERVATION_POLICY_UNAVAILABLE");
    }
}
