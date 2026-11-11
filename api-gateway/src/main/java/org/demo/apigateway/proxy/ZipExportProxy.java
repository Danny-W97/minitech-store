package org.demo.apigateway.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.demo.apigateway.config.ResilientHttpExecutor;
import org.demo.platformobservability.CorrelationIdContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Synchronous servlet streaming keeps downstream ownership and backpressure for the whole transfer. */
@Component
public class ZipExportProxy {
    private final RestClient client;
    private final ResilientHttpExecutor resilience;
    private final Semaphore slots;
    private static final Set<String> HOP_HEADERS = Set.of("connection", "content-length", "transfer-encoding", "host",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade");

    public ZipExportProxy(RestClient.Builder builder, ResilientHttpExecutor resilience,
            @Value("${gateway.export.max-concurrent:2}") int concurrent,
            @Value("${gateway.export.connect-timeout-ms:1000}") int connectTimeout,
            @Value("${gateway.export.read-timeout-ms:30000}") int readTimeout) {
        if (concurrent < 1 || connectTimeout < 1 || readTimeout < 1) throw new IllegalArgumentException("Invalid export proxy limits");
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeout); factory.setReadTimeout(readTimeout);
        client = builder.clone().requestFactory(factory).build();
        this.resilience = resilience; slots = new Semaphore(concurrent);
    }

    public void forward(String baseUrl, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!slots.tryAcquire()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Export capacity exhausted; retry later");
        String query = request.getQueryString();
        try {
            URI target = URI.create(baseUrl + "/invoices/export/zip" + (query == null ? "" : "?" + query));
            resilience.execute("billing-export", () -> client.get().uri(target).headers(headers -> {
                var excluded = new HashSet<>(HOP_HEADERS);
                String connection = request.getHeader("Connection");
                if (connection != null) for (String token : connection.split(",")) excluded.add(token.trim().toLowerCase(Locale.ROOT));
                var names = request.getHeaderNames();
                if (names != null) while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    if (!excluded.contains(name.toLowerCase(Locale.ROOT))) headers.addAll(name, java.util.Collections.list(request.getHeaders(name)));
                }
                headers.set(CorrelationIdContext.HEADER_NAME, CorrelationIdContext.currentOrNew());
                headers.set(CorrelationIdContext.TRACEPARENT_HEADER, CorrelationIdContext.childTraceparent());
            }).exchange((outgoing, incoming) -> {
                if (incoming.getStatusCode().is5xxServerError()) throw new RestClientException("Billing export unavailable");
                if (incoming.getStatusCode().is3xxRedirection()) throw new RestClientException("Billing export redirect refused");
                byte[] buffer = new byte[32768];
                var input = incoming.getBody();
                int length = input.read(buffer);
                response.setStatus(incoming.getStatusCode().value());
                var excluded = new HashSet<>(HOP_HEADERS);
                for (String value : incoming.getHeaders().getOrEmpty(HttpHeaders.CONNECTION))
                    for (String token : value.split(",")) excluded.add(token.trim().toLowerCase(Locale.ROOT));
                incoming.getHeaders().forEach((name, values) -> {
                    if (!excluded.contains(name.toLowerCase(Locale.ROOT))) {
                        response.setHeader(name, values.getFirst());
                        values.stream().skip(1).forEach(value -> response.addHeader(name, value));
                    }
                });
                String traceparent = org.slf4j.MDC.get(CorrelationIdContext.MDC_TRACEPARENT);
                if (traceparent != null && response.getHeader(CorrelationIdContext.TRACEPARENT_HEADER) == null) {
                    response.setHeader(CorrelationIdContext.TRACEPARENT_HEADER, traceparent);
                }
                var output = response.getOutputStream();
                while (length != -1) {
                    try { output.write(buffer, 0, length); output.flush(); }
                    catch (IOException disconnected) { throw new ClientDisconnected(disconnected); }
                    length = input.read(buffer);
                }
                return null;
            }), false); // A partially delivered ZIP must never be retried or concatenated.
        } catch (ClientDisconnected disconnected) {
            throw (IOException) disconnected.getCause();
        } catch (IllegalArgumentException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid export query", invalid);
        } catch (RestClientException unavailable) {
            if (response.isCommitted()) throw new IOException("ZIP transfer interrupted", unavailable);
            throw new GatewayUnavailableException("Billing export is unavailable", unavailable);
        } finally { slots.release(); }
    }
    private static final class ClientDisconnected extends RuntimeException {
        ClientDisconnected(IOException cause) { super(cause); }
    }
}
