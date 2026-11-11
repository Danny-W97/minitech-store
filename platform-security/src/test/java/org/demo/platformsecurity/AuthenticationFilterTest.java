package org.demo.platformsecurity;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationFilterTest {
    private HttpServer issuer;
    private OncePerRequestFilter filter;
    @BeforeEach void start() throws Exception {
        issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer.createContext("/internal/auth/introspect", exchange -> {
            assertEquals("own-service-secret", exchange.getRequestHeaders().getFirst("X-Service-Token"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = body.contains("customer-one") ? "{\"role\":\"CUSTOMER\",\"customerId\":17,\"email\":\"one@example.com\"}"
                    : body.contains("operator-token") ? "{\"role\":\"OPERATOR\"}" : "{\"role\":\"ANONYMOUS\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        issuer.start();
        filter = new PlatformSecurityConfiguration().platformAuthenticationFilter("wishlist-service",
                "http://127.0.0.1:" + issuer.getAddress().getPort(), "own-service-secret").getFilter();
    }
    @AfterEach void stop() { issuer.stop(0); }
    @Test void directServicePortChecksSubjectAndIgnoresSpoofedHeaders() throws Exception {
        assertEquals(200, call("/wishlists/customer/17", "customer-one", false));
        assertEquals(403, call("/wishlists/customer/18", "customer-one", false));
        assertEquals(401, call("/wishlists/customer/17", null, true));
        assertEquals(401, call("/orders", "forged-token", true));
        assertEquals(403, call("/orders", "customer-one", true));
        assertEquals(200, call("/orders", "operator-token", false));
    }
    @Test void authenticationOutageFailsClosedButCatalogRemainsAvailable() throws Exception {
        issuer.stop(0);
        assertEquals(503, call("/wishlists/customer/17", "customer-one", false));
        assertEquals(200, call("/products", null, false));
        assertEquals(401, call("/orders", null, false));
    }
    private int call(String path, String token, boolean forgedHeaders) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        if (forgedHeaders) {
            request.addHeader("X-Customer-Id", "17"); request.addHeader("X-Role", "OPERATOR");
            request.addHeader("X-Service-Token", "own-service-secret");
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));
        return response.getStatus();
    }
}
