package org.demo.platformsecurity.privacy;

import com.sun.net.httpserver.HttpServer;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

class CommerceRetentionInvalidationClientTest {
    HttpServer server;
    final Subject subject = new Subject(17, 23, 4, UUID.fromString("00000000-0000-0000-0000-000000000007"));
    @AfterEach void stop() { if (server != null) server.stop(0); }
    String start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CommerceRetentionInvalidationClient.PATH, handler); server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test void transportUsesServiceCredentialAndOnlyTheExactInvalidationScope() throws Exception {
        var bodies = new ArrayList<String>(); var headers = new ArrayList<String>();
        var origin = start(exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1); exchange.close();
        });
        var client = new CommerceRetentionInvalidationClient(origin, new ServiceAuthenticationInterceptor("fixture-service-only"));
        client.invalidate(subject, Phase.REDACTION); client.invalidate(subject, Phase.REDACTION);
        assertThat(headers).containsExactly("Bearer fixture-service-only", "Bearer fixture-service-only");
        assertThat(bodies).hasSize(2);
        var json = JsonMapper.builder().build().readTree(bodies.getFirst());
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.path("orderId").asLong()).isEqualTo(17);
        assertThat(json.path("generation").asText()).isEqualTo(subject.chainGeneration().toString());
        assertThat(json.path("orderVersion").asLong()).isEqualTo(4);
        assertThat(json.path("phase").asText()).isEqualTo("REDACTION");
        assertThat(bodies.getFirst()).doesNotContain("customerId", "email", "proof", "confirmed", "fixture-service-only");
    }

    @Test void unexpectedStatusOrBodyIsRejectedWithoutEchoingResponseContent() throws Exception {
        var calls = new AtomicInteger();
        var origin = start(exchange -> {
            byte[] body = "synthetic-never-echo".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(calls.getAndIncrement() == 0 ? 503 : 202, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        var client = new CommerceRetentionInvalidationClient(origin, new ServiceAuthenticationInterceptor("fixture-service-only"));
        for (int n = 0; n < 2; n++) assertThatThrownBy(() -> client.invalidate(subject, Phase.REDACTION))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RETENTION_INVALIDATION_")
                .hasMessageNotContaining("synthetic-never-echo");
    }

    @Test void originCannotContainCredentialsPathQueryFragmentOrInvalidPort() {
        for (String origin : List.of("file:///tmp/fixture", "http://user:fixture-secret@localhost", "http://localhost/prefix",
                "http://localhost?token=fixture-secret", "http://localhost#fixture-secret", "http://localhost:0",
                "http://localhost:65536", "http://invalid host")) {
            assertThatThrownBy(() -> new CommerceRetentionInvalidationClient(origin, new ServiceAuthenticationInterceptor("fixture")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid retention invalidation origin");
        }
    }

    @Test void missingServiceCredentialCannotSendAnAnonymousCallback() throws Exception {
        var calls = new AtomicInteger();
        var origin = start(exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(202, -1); exchange.close(); });
        var client = new CommerceRetentionInvalidationClient(origin, new ServiceAuthenticationInterceptor(""));
        assertThatThrownBy(() -> client.invalidate(subject, Phase.REDACTION)).isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isZero();
    }
}
