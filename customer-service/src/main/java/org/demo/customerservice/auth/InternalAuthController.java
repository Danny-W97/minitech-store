package org.demo.customerservice.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.demo.customerservice.customer.CustomerRepository;
import org.demo.platformsecurity.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;

/** Only this issuer holds the credential registry and JWT signing keys. */
@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {
    private final JwtService jwt;
    private final CustomerRepository customers;
    private final Map<String, String> services;
    private final String operatorToken;
    public InternalAuthController(JwtService jwt, CustomerRepository customers, ObjectMapper mapper,
            @Value("${PLATFORM_SERVICE_CREDENTIALS_JSON:}") String credentials,
            @Value("${PLATFORM_OPERATOR_TOKEN:}") String operatorToken) {
        this.jwt = jwt;
        this.customers = customers;
        this.operatorToken = operatorToken;
        try {
            this.services = credentials.isBlank() ? Map.of() : Map.copyOf(mapper.readValue(credentials, new TypeReference<Map<String, String>>() {}));
        } catch (Exception exception) { throw new IllegalStateException("Invalid platform credential registry"); }
        if (services.values().stream().anyMatch(value -> !validSecret(value))
                || new HashSet<>(services.values()).size() != services.size()
                || !operatorToken.isEmpty() && (!validSecret(operatorToken) || services.containsValue(operatorToken))) {
            throw new IllegalStateException("Platform credentials must be unique random tokens of at least 32 bytes");
        }
    }
    @PostMapping("/introspect")
    public Actor introspect(@RequestHeader(value = "X-Service-Token", required = false) String caller, @RequestBody TokenRequest request) {
        if (serviceFor(caller) == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Service authentication required");
        return resolve(request.token());
    }
    Actor resolve(String token) {
        if (token == null || token.length() > 8192) return Actor.ANONYMOUS;
        String service = serviceFor(token);
        if (service != null) return new Actor("SERVICE", service, null, null);
        if (same(operatorToken, token)) return new Actor("OPERATOR", null, null, null);
        return jwt.parseAccessToken(token).flatMap(claims -> customers.findById(claims.customerId())
                        .filter(customer -> customer.isEnabled() && customer.getEmail().equals(claims.email()))
                        .map(customer -> new Actor("CUSTOMER", null, customer.getId(), customer.getEmail())))
                .orElse(Actor.ANONYMOUS);
    }
    private String serviceFor(String token) {
        return services.entrySet().stream().filter(entry -> same(entry.getValue(), token)).map(Map.Entry::getKey).findFirst().orElse(null);
    }
    private static boolean validSecret(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43,128}"); }
    private static boolean same(String expected, String actual) {
        return expected != null && !expected.isEmpty() && actual != null && actual.length() <= 8192
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
    public record TokenRequest(String token) {}
}
