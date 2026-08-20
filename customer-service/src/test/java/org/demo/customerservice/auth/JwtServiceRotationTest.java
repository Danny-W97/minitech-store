package org.demo.customerservice.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceRotationTest {

    @Test
    void keepsLegacyHmacModeWorkingUntilProfileIsRotated() {
        String legacySecret = Base64.getEncoder().encodeToString("a-secret-that-is-at-least-32-bytes-long".getBytes());
        JwtService service = new JwtService(
                new ObjectMapper(),
                legacySecret,
                "",
                "",
                "",
                900
        );

        String token = service.createAccessToken(new CustomerPrincipal(7L, "legacy@example.com"));

        assertThat(service.rsaMode()).isFalse();
        assertThat(service.parseAccessToken(token)).isPresent();
        assertThat((java.util.List<?>) service.jwks().get("keys")).isEmpty();
    }

    @Test
    void createsAndParsesRs256TokenUsingKidAndPublishesJwks() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String keyId = "rsa-test-1";
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String publicKeysJson = new ObjectMapper().writeValueAsString(Map.of(keyId, publicKey));

        JwtService service = new JwtService(
                new ObjectMapper(),
                "",
                keyId,
                privateKey,
                publicKeysJson,
                900
        );

        String token = service.createAccessToken(new CustomerPrincipal(42L, "test@example.com"));

        assertThat(service.rsaMode()).isTrue();
        assertThat(service.activeKeyId()).isEqualTo(keyId);
        JwtClaims claims = service.parseAccessToken(token).orElseThrow();
        assertThat(claims.customerId()).isEqualTo(42L);
        assertThat(claims.email()).isEqualTo("test@example.com");
        assertThat(service.jwks()).containsKey("keys");
        assertThat((java.util.List<?>) service.jwks().get("keys")).hasSize(1);
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        assertThat(header).contains("\"kid\":\"rsa-test-1\"");
    }
}
