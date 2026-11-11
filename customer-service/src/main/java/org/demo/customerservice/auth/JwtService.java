package org.demo.customerservice.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtService {

    private static final String LEGACY_ALGORITHM = "HmacSHA256";
    private static final String LEGACY_HEADER_ALGORITHM = "HS256";
    private static final String RSA_ALGORITHM = "SHA256withRSA";
    private static final String RSA_HEADER_ALGORITHM = "RS256";

    @org.springframework.beans.factory.annotation.Autowired private RestoreEpochStore restoreEpoch;
    private final ObjectMapper objectMapper;
    private final SecretKeySpec legacySigningKey;
    private final PrivateKey rsaSigningKey;
    private final Map<String, PublicKey> rsaVerificationKeys;
    private final String activeKeyId;
    private final long accessTokenTtlSeconds;
    private final boolean rsaMode;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder secretDecoder = Base64.getDecoder();
    private final Base64.Decoder tokenDecoder = Base64.getUrlDecoder();

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${customer.auth.jwt-secret:}") String base64Secret,
            @Value("${customer.auth.jwt.active-kid:}") String configuredActiveKeyId,
            @Value("${customer.auth.jwt.private-key-base64:}") String privateKeyBase64,
            @Value("${customer.auth.jwt.public-keys-json:}") String publicKeysJson,
            @Value("${customer.auth.access-token-ttl-seconds:900}") long accessTokenTtlSeconds
    ) {
        this.objectMapper = objectMapper;
        if (accessTokenTtlSeconds < 60) {
            throw new IllegalStateException("customer.auth.access-token-ttl-seconds must be at least 60");
        }
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;

        boolean anyRsaSetting = hasText(configuredActiveKeyId)
                || hasText(privateKeyBase64)
                || hasText(publicKeysJson);
        boolean completeRsaSetting = hasText(configuredActiveKeyId)
                && hasText(privateKeyBase64)
                && hasText(publicKeysJson);
        if (anyRsaSetting && !completeRsaSetting) {
            throw new IllegalStateException(
                    "CUSTOMER_JWT_ACTIVE_KID, CUSTOMER_JWT_PRIVATE_KEY_BASE64 and "
                            + "CUSTOMER_JWT_PUBLIC_KEYS_JSON must be configured together"
            );
        }

        if (completeRsaSetting) {
            this.rsaMode = true;
            this.activeKeyId = configuredActiveKeyId.trim();
            this.legacySigningKey = null;
            this.rsaSigningKey = decodePrivateKey(privateKeyBase64);
            this.rsaVerificationKeys = decodePublicKeys(publicKeysJson);
            if (!rsaVerificationKeys.containsKey(activeKeyId)) {
                throw new IllegalStateException("Active JWT key is missing from CUSTOMER_JWT_PUBLIC_KEYS_JSON");
            }
            try {
                String probe = "MiniTech JWT key pair validation";
                if (!verify(probe, encoder.encodeToString(sign(probe)), rsaVerificationKeys.get(activeKeyId))) {
                    throw new GeneralSecurityException();
                }
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Active JWT private and public keys do not match");
            }
        } else {
            this.rsaMode = false;
            this.activeKeyId = "legacy";
            this.rsaSigningKey = null;
            this.rsaVerificationKeys = Map.of();
            if (!hasText(base64Secret)) {
                throw new IllegalStateException("CUSTOMER_JWT_SECRET is required until RSA JWT keys are configured");
            }
            byte[] secret;
            try {
                secret = secretDecoder.decode(base64Secret);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("CUSTOMER_JWT_SECRET must be valid Base64", exception);
            }
            if (secret.length < 32) {
                throw new IllegalStateException("CUSTOMER_JWT_SECRET must contain at least 32 decoded bytes");
            }
            this.legacySigningKey = new SecretKeySpec(secret, LEGACY_ALGORITHM);
        }
    }

    public String createAccessToken(CustomerPrincipal principal) {
        return createAccessToken(principal, "pwd");
    }

    public String createAccessToken(CustomerPrincipal principal, String authenticationMethod) {
        if(restoreEpoch!=null&&!restoreEpoch.permitsLogin())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Restored environment requires reconciliation");
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenTtlSeconds);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", principal.customerId().toString());
        payload.put("email", principal.email());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        payload.put("typ", "access");
        payload.put("auth_epoch",restoreEpoch==null?"legacy":restoreEpoch.current());
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("amr", authenticationMethod == null || authenticationMethod.isBlank()
                ? List.of("pwd") : List.of(authenticationMethod.split("\\+")));

        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", rsaMode ? RSA_HEADER_ALGORITHM : LEGACY_HEADER_ALGORITHM);
            header.put("typ", "JWT");
            header.put("kid", activeKeyId);
            String encodedHeader = encoder.encodeToString(objectMapper.writeValueAsBytes(header));
            String encodedPayload = encoder.encodeToString(objectMapper.writeValueAsBytes(payload));
            String content = encodedHeader + "." + encodedPayload;
            return content + "." + encoder.encodeToString(sign(content));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create access token", exception);
        }
    }

    public Optional<JwtClaims> parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode header = objectMapper.readTree(new String(tokenDecoder.decode(parts[0]), StandardCharsets.UTF_8));
            String algorithm = header.path("alg").asText("");
            String keyId = header.path("kid").asText("");
            if (!"JWT".equals(header.path("typ").asText(""))) {
                return Optional.empty();
            }
            if (rsaMode) {
                if (!RSA_HEADER_ALGORITHM.equals(algorithm) || keyId.isBlank()
                        || !verify(parts[0] + "." + parts[1], parts[2], rsaVerificationKeys.get(keyId))) {
                    return Optional.empty();
                }
            } else if (!LEGACY_HEADER_ALGORITHM.equals(algorithm)
                    || (!keyId.isBlank() && !activeKeyId.equals(keyId))) {
                return Optional.empty();
            } else {
                byte[] expectedSignature = sign(parts[0] + "." + parts[1]);
                byte[] actualSignature = tokenDecoder.decode(parts[2]);
                if (!java.security.MessageDigest.isEqual(expectedSignature, actualSignature)) {
                    return Optional.empty();
                }
            }

            JsonNode payload = objectMapper.readTree(new String(tokenDecoder.decode(parts[1]), StandardCharsets.UTF_8));
            if (!"access".equals(payload.path("typ").asText(""))) {
                return Optional.empty();
            }
            if(restoreEpoch!=null&&(!restoreEpoch.permitsLogin()||!restoreEpoch.current().equals(payload.path("auth_epoch").asText("legacy"))))return Optional.empty();
            long expiresAt = payload.path("exp").asLong(0);
            long customerId = Long.parseLong(payload.path("sub").asText());
            String email = payload.path("email").asText("");
            if (expiresAt <= Instant.now().getEpochSecond() || customerId < 1 || email.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new JwtClaims(customerId, email, Instant.ofEpochSecond(expiresAt)));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public java.util.Set<String> authenticationMethods(String token) {
        if(parseAccessToken(token).isEmpty())return java.util.Set.of();
        try {
            var methods=objectMapper.readTree(tokenDecoder.decode(token.split("\\.")[1])).path("amr");
            java.util.Set<String> result=new java.util.HashSet<>();
            if(methods.isArray())for(var method:methods)if(method.isTextual())result.add(method.asText());
            return java.util.Set.copyOf(result);
        }catch(Exception invalid){return java.util.Set.of();}
    }

    public Map<String, Object> jwks() {
        List<Map<String, Object>> keys = new ArrayList<>();
        if (rsaMode) {
            rsaVerificationKeys.forEach((keyId, publicKey) -> {
                RSAPublicKey rsaKey = (RSAPublicKey) publicKey;
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("kty", "RSA");
                key.put("use", "sig");
                key.put("alg", RSA_HEADER_ALGORITHM);
                key.put("kid", keyId);
                key.put("n", encoder.encodeToString(unsignedBytes(rsaKey.getModulus().toByteArray())));
                key.put("e", encoder.encodeToString(unsignedBytes(rsaKey.getPublicExponent().toByteArray())));
                keys.add(key);
            });
        }
        return Map.of("keys", keys);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public boolean rsaMode() {
        return rsaMode;
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    private byte[] sign(String content) throws GeneralSecurityException {
        if (rsaMode) {
            Signature signature = Signature.getInstance(RSA_ALGORITHM);
            signature.initSign(rsaSigningKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        }
        Mac mac = Mac.getInstance(LEGACY_ALGORITHM);
        mac.init(legacySigningKey);
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }

    private boolean verify(String content, String encodedSignature, PublicKey publicKey) throws GeneralSecurityException {
        if (publicKey == null) {
            return false;
        }
        Signature signature = Signature.getInstance(RSA_ALGORITHM);
        signature.initVerify(publicKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return signature.verify(tokenDecoder.decode(encodedSignature));
    }

    private PrivateKey decodePrivateKey(String value) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(secretDecoder.decode(value.trim()))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("CUSTOMER_JWT_PRIVATE_KEY_BASE64 is not a valid RSA PKCS#8 key", exception);
        }
    }

    private Map<String, PublicKey> decodePublicKeys(String json) {
        try {
            Map<String, String> encodedKeys = objectMapper.readValue(
                    json,
                    new TypeReference<>() {
                    }
            );
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            encodedKeys.forEach((keyId, encodedKey) -> {
                try {
                    var key = keyFactory.generatePublic(new X509EncodedKeySpec(secretDecoder.decode(encodedKey)));
                    if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048) {
                        throw new GeneralSecurityException("RSA JWT keys require at least 2048 bits");
                    }
                    keys.put(keyId, key);
                } catch (Exception exception) {
                    throw new IllegalStateException("Invalid public JWT key for kid " + keyId, exception);
                }
            });
            if (keys.isEmpty()) {
                throw new IllegalStateException("CUSTOMER_JWT_PUBLIC_KEYS_JSON must contain at least one key");
            }
            return Map.copyOf(keys);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("CUSTOMER_JWT_PUBLIC_KEYS_JSON is not valid JSON", exception);
        }
    }

    private byte[] unsignedBytes(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
