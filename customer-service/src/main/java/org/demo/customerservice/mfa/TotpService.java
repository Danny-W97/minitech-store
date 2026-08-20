package org.demo.customerservice.mfa;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int TIME_STEP_SECONDS = 30;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateSecret() {
        byte[] bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public String otpauthUri(String email, String issuer, String secret) {
        return "otpauth://totp/" + encode(issuer + ":" + email)
                + "?secret=" + secret
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=6&period=" + TIME_STEP_SECONDS;
    }

    public Verification verify(String secret, String code, long nowEpochSeconds, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return Verification.invalid();
        }
        long currentStep = Math.floorDiv(nowEpochSeconds, TIME_STEP_SECONDS);
        for (long offset = -1; offset <= 1; offset++) {
            long step = currentStep + offset;
            if (step < 0 || (lastUsedStep != null && step <= lastUsedStep)) {
                continue;
            }
            if (MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),
                    codeForStep(secret, step).getBytes(StandardCharsets.US_ASCII))) {
                return new Verification(true, step);
            }
        }
        return Verification.invalid();
    }

    public String codeForStep(String secret, long step) {
        try {
            byte[] key = decodeBase32(secret);
            byte[] counter = new byte[8];
            for (int i = 7; i >= 0; i--) {
                counter[i] = (byte) (step & 0xff);
                step >>>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not calculate TOTP", exception);
        }
    }

    public String generateRecoveryCode() {
        byte[] bytes = new byte[8];
        secureRandom.nextBytes(bytes);
        return encodeBase32(bytes).substring(0, 12);
    }

    public String hashRecoveryCode(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(code.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash recovery code", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                result.append(BASE32.charAt((buffer >> bits) & 31));
            }
        }
        if (bits > 0) {
            result.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return result.toString();
    }

    private byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").replace(" ", "").toUpperCase();
        byte[] result = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char character : normalized.toCharArray()) {
            int digit = BASE32.indexOf(character);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base32 TOTP secret");
            }
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                result[index++] = (byte) ((buffer >> bits) & 0xff);
            }
        }
        return result;
    }

    public record Verification(boolean valid, long timeStep) {
        static Verification invalid() { return new Verification(false, -1); }
    }
}
