package org.demo.customerservice.mfa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Encryption keys are independent of JWT. Legacy decryption requires an explicitly preserved key. */
@Service
public class MfaEncryptionService {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecretKeySpec legacyKey;
    private final SecureRandom random = new SecureRandom();

    public MfaEncryptionService(
            @Value("${customer.auth.mfa-encryption-key-material:}") String activeKey,
            @Value("${customer.auth.mfa-active-kid:mfa-v1}") String activeKeyId,
            @Value("${customer.auth.mfa-previous-keys-json:}") String previousKeys,
            @Value("${customer.auth.mfa-legacy-key-base64:}") String legacyKey) {
        this.activeKeyId = validId(activeKeyId);
        Map<String, SecretKeySpec> configured = new HashMap<>();
        if (previousKeys != null && !previousKeys.isBlank()) {
            try {
                Map<String, String> old = new ObjectMapper().readValue(previousKeys, new TypeReference<>() {});
                if (old == null || old.size() > 32) throw new IllegalArgumentException();
                old.forEach((id, value) -> configured.put(validId(id), decodeKey(value)));
            } catch (Exception exception) {
                throw new IllegalStateException("Invalid CUSTOMER_MFA_PREVIOUS_KEYS_JSON");
            }
        }
        if (configured.containsKey(this.activeKeyId)) throw new IllegalStateException("Active MFA key id must not occur in previous keys");
        configured.put(this.activeKeyId, decodeKey(activeKey));
        this.keys = Map.copyOf(configured);
        this.legacyKey = legacyKey == null || legacyKey.isBlank() ? null : decodeKey(legacyKey);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            String prefix = "v1:" + activeKeyId + ":";
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, iv));
            cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt MFA secret");
        }
    }

    public String decrypt(String ciphertext) {
        try {
            if (ciphertext == null || ciphertext.length() > 4096) throw new IllegalArgumentException();
            String[] parts = ciphertext.split(":", -1);
            SecretKeySpec key;
            String payload;
            String prefix = null;
            if (parts.length == 1) {
                key = legacyKey;
                payload = ciphertext;
            } else {
                if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
                key = keys.get(validId(parts[1]));
                payload = parts[2];
                prefix = "v1:" + parts[1] + ":";
            }
            if (key == null) throw new IllegalArgumentException();
            byte[] combined = Base64.getUrlDecoder().decode(payload);
            if (combined.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(combined, 0, 12)));
            if (prefix != null) cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(combined, 12, combined.length)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt MFA secret; restore the required MFA key before migration");
        }
    }

    public boolean requiresMigration(String ciphertext) { return !ciphertext.startsWith("v1:" + activeKeyId + ":"); }
    public String activeKeyId() { return activeKeyId; }

    private static String validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalStateException("Invalid MFA key id");
        return id;
    }
    private static SecretKeySpec decodeKey(String value) {
        try {
            byte[] key = Base64.getDecoder().decode(value == null ? "" : value);
            if (key.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MFA encryption keys must contain exactly 32 Base64-encoded bytes");
        }
    }
}
