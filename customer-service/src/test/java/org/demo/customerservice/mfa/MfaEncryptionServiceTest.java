package org.demo.customerservice.mfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MfaEncryptionServiceTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String OTHER = Base64.getEncoder().encodeToString("11111111111111111111111111111111".getBytes(StandardCharsets.UTF_8));
    static String legacyKey(String material) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
    }
    static String legacyCipher(String material, String secret) throws Exception {
        byte[] iv = new byte[12];
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(legacyKey(material)), "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] combined = java.util.Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }
    @Test void versionedEncryptionUsesRandomNoncesAndAuthenticatesHeader() throws Exception {
        var crypto = new MfaEncryptionService(KEY, "key1", "", "");
        String encrypted = crypto.encrypt("TOTP-SECRET");
        assertThat(encrypted).startsWith("v1:key1:").isNotEqualTo(crypto.encrypt("TOTP-SECRET"));
        assertThat(crypto.decrypt(encrypted)).isEqualTo("TOTP-SECRET");
        var renamedKey = new MfaEncryptionService(KEY, "key2", "", "");
        assertThatThrownBy(() -> renamedKey.decrypt(encrypted.replace("key1", "key2"))).hasMessageContaining("Could not decrypt");
    }
    @Test void previousKeysDecryptUntilMigratedThenMayBeRetired() throws Exception {
        var old = new MfaEncryptionService(KEY, "old", "", "");
        String cipher = old.encrypt("SECRET");
        var rotated = new MfaEncryptionService(OTHER, "new", new ObjectMapper().writeValueAsString(Map.of("old", KEY)), "");
        assertThat(rotated.requiresMigration(cipher)).isTrue();
        String migrated = rotated.encrypt(rotated.decrypt(cipher));
        var retired = new MfaEncryptionService(OTHER, "new", "", "");
        assertThat(retired.decrypt(migrated)).isEqualTo("SECRET");
        assertThat(retired.requiresMigration(migrated)).isFalse();
        assertThatThrownBy(() -> retired.decrypt(cipher)).hasMessageContaining("Could not decrypt");
    }
    @Test void legacyDecryptionRequiresExplicitPreservedDerivedKey() throws Exception {
        String cipher = legacyCipher("old-rsa-material", "SECRET");
        var crypto = new MfaEncryptionService(KEY, "new", "", legacyKey("old-rsa-material"));
        assertThat(crypto.decrypt(cipher)).isEqualTo("SECRET");
        assertThat(crypto.requiresMigration(cipher)).isTrue();
        assertThatThrownBy(() -> new MfaEncryptionService(KEY, "new", "", "").decrypt(cipher)).hasMessageContaining("Could not decrypt");
    }
    @Test void missingMalformedOrShortKeysAreRejected() {
        for (String key : new String[]{"", "not-base64", Base64.getEncoder().encodeToString(new byte[31])}) {
            assertThatThrownBy(() -> new MfaEncryptionService(key, "key", "", "")).hasMessageContaining("32");
        }
        assertThatThrownBy(() -> new MfaEncryptionService(KEY, "bad:id", "", "")).hasMessageContaining("key id");
        assertThatThrownBy(() -> new MfaEncryptionService(KEY, "key", "{broken", "")).hasMessageContaining("PREVIOUS_KEYS_JSON");
    }
    @Test void corruptedOrUnknownCiphertextsAreNeverAccepted() {
        var crypto = new MfaEncryptionService(KEY, "key", "", "");
        for (String cipher : new String[]{"short", "v2:key:AAAA", "v1:unknown:AAAA", crypto.encrypt("secret") + "AAAA"}) {
            assertThatThrownBy(() -> crypto.decrypt(cipher)).hasMessageContaining("Could not decrypt");
        }
    }
}
