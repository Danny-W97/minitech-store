package org.demo.customerservice.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.customer.CustomerRepository;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalAuthControllerTest {
    private final String serviceToken = "s".repeat(43);
    private final String operatorToken = "o".repeat(43);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JwtService jwt = new JwtService(mapper, Base64.getEncoder().encodeToString(new byte[48]), "", "", "", 900);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private InternalAuthController issuer() throws Exception {
        return new InternalAuthController(jwt, customers, mapper,
                mapper.writeValueAsString(Map.of("order-service", serviceToken)), operatorToken);
    }
    @Test void authenticatesCustomerOperatorAndServiceWithoutAcceptingForgedTokens() throws Exception {
        Customer customer = new Customer("one@example.com", "hash", "One", "Test", "Street", "00-001", "City", "PL");
        ReflectionTestUtils.setField(customer, "id", 17L);
        ReflectionTestUtils.setField(customer, "enabled", true);
        when(customers.findById(17L)).thenReturn(Optional.of(customer));
        var issuer = issuer();
        String token = jwt.createAccessToken(new CustomerPrincipal(17L, "one@example.com"));
        assertEquals("CUSTOMER", issuer.introspect(serviceToken, new InternalAuthController.TokenRequest(token)).role());
        assertEquals("order-service", issuer.introspect(serviceToken, new InternalAuthController.TokenRequest(serviceToken)).service());
        assertTrue(issuer.introspect(serviceToken, new InternalAuthController.TokenRequest(operatorToken)).operator());
        assertEquals(Actor.ANONYMOUS, issuer.introspect(serviceToken, new InternalAuthController.TokenRequest("forged")));
        assertThrows(ResponseStatusException.class, () -> issuer.introspect("spoofed", new InternalAuthController.TokenRequest(token)));
        ReflectionTestUtils.setField(customer, "enabled", false);
        assertEquals(Actor.ANONYMOUS, issuer.introspect(serviceToken, new InternalAuthController.TokenRequest(token)));
    }
    @Test void rejectsExpiredJwtAndDuplicateOrWeakServiceCredentials() throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(Map.of(
                "sub", "17", "email", "one@example.com", "exp", 1, "typ", "access")));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(new byte[48], "HmacSHA256"));
        String token = header + "." + payload + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((header + "." + payload).getBytes()));
        assertEquals(Actor.ANONYMOUS, issuer().introspect(serviceToken, new InternalAuthController.TokenRequest(token)));
        assertThrows(IllegalStateException.class, () -> new InternalAuthController(jwt, customers, mapper,
                mapper.writeValueAsString(Map.of("order-service", "weak")), operatorToken));
        assertThrows(IllegalStateException.class, () -> new InternalAuthController(jwt, customers, mapper,
                mapper.writeValueAsString(Map.of("order-service", serviceToken)), serviceToken));
    }
}
