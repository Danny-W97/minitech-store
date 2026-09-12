package org.demo.customerservice.mfa;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MfaEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaEncryptionService(
            @Value("${customer.auth.mfa-encryption-key-material:}") String directKeyMaterial,
            @Value("${customer.auth.jwt.private-key-base64:}") String rsaPrivateKey,
            @Value("${customer.auth.jwt-secret:}") String jwtSecret
    ) {
        String keyMaterial = null;
        if (directKeyMaterial != null && !directKeyMaterial.isBlank()) {
            keyMaterial = directKeyMaterial;
        } else if (rsaPrivateKey != null && !rsaPrivateKey.isBlank()) {
            keyMaterial = rsaPrivateKey;
        } else if (jwtSecret != null && !jwtSecret.isBlank()) {
            keyMaterial = jwtSecret;
        }
        if (keyMaterial == null) {
            throw new IllegalStateException("CUSTOMER_MFA_ENCRYPTION_KEY_BASE64 or JWT key material is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Could not initialize MFA encryption", exception);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt MFA secret", exception);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getUrlDecoder().decode(ciphertext);
            if (combined.length <= 12) {
                throw new IllegalArgumentException("Encrypted MFA value is too short");
            }
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt MFA secret", exception);
        }
    }
}
