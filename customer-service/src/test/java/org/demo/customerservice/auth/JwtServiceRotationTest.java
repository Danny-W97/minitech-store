package org.demo.customerservice.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceRotationTest {
    @Test void privacyAuthenticationMethodsComeOnlyFromAnAuthenticatedSignature() {
        var service=new JwtService(new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[48]),"","","",900);
        String token=service.createAccessToken(new CustomerPrincipal(1L,"owner@fixture.example"),"pwd+totp");
        assertThat(service.authenticationMethods(token)).containsExactlyInAnyOrder("pwd","totp");
        String[] parts=token.split("\\.");parts[1]=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"typ\":\"access\",\"sub\":\"1\",\"email\":\"owner@fixture.example\",\"amr\":[\"webauthn\"]}".getBytes());
        assertThat(service.authenticationMethods(String.join(".",parts))).isEmpty();
    }

    private KeyPair pair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        return generator.generateKeyPair();
    }
    private JwtService rsa(String active, KeyPair signing, Map<String, KeyPair> publicKeys) throws Exception {
        var keys = new java.util.HashMap<String, String>();
        publicKeys.forEach((id, key) -> keys.put(id, Base64.getEncoder().encodeToString(key.getPublic().getEncoded())));
        return new JwtService(new ObjectMapper(), "", active, Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()),
                new ObjectMapper().writeValueAsString(keys), 900);
    }
    @Test void rotationRetainsPreviousTokensUntilTheirPublicKeyIsRetired() throws Exception {
        var first = pair(); var second = pair();
        var old = rsa("first", first, Map.of("first", first));
        var principal = new CustomerPrincipal(7L, "rotate@example.test");
        String token = old.createAccessToken(principal);
        var rotated = rsa("second", second, Map.of("first", first, "second", second));
        assertThat(rotated.parseAccessToken(token)).isPresent();
        assertThat(rotated.parseAccessToken(rotated.createAccessToken(principal))).isPresent();
        assertThat((java.util.List<?>) rotated.jwks().get("keys")).hasSize(2);
        assertThat(rsa("second", second, Map.of("second", second)).parseAccessToken(token)).isEmpty();
    }
    @Test void mismatchedActiveKeyPairPreventsStartup() throws Exception {
        var first = pair(); var second = pair();
        assertThatThrownBy(() -> rsa("active", first, Map.of("active", second))).hasMessageContaining("do not match");
    }

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
