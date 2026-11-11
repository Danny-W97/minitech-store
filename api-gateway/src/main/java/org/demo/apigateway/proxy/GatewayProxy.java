package org.demo.apigateway.proxy;

import jakarta.servlet.http.HttpServletRequest;
import org.demo.apigateway.config.ResilientHttpExecutor;
import org.demo.platformobservability.CorrelationIdContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.Set;

@Component
public class GatewayProxy {

    private static final Logger log = LoggerFactory.getLogger(GatewayProxy.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "host",
            "transfer-encoding"
    );

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public GatewayProxy(RestClient.Builder restClientBuilder, ResilientHttpExecutor resilientHttpExecutor) {
        this.restClient = restClientBuilder.build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public ResponseEntity<byte[]> forward(
            String serviceName,
            String baseUrl,
            HttpServletRequest sourceRequest,
            byte[] body
    ) {
        URI target = targetUri(baseUrl, sourceRequest);
        HttpMethod method = HttpMethod.valueOf(sourceRequest.getMethod());

        try {
            log.info("Forwarding {} {} to downstream", method, target.getPath());
            return resilientHttpExecutor.execute(serviceName, () -> {
                RestClient.RequestBodySpec requestSpec = restClient.method(method)
                        .uri(target)
                        .headers(headers -> copyRequestHeaders(sourceRequest, headers));
                ResponseEntity<byte[]> response = body != null && body.length > 0
                        ? requestSpec.body(body).exchange(this::toResponse)
                        : requestSpec.exchange(this::toResponse);
                if (response.getStatusCode().is5xxServerError()) {
                    throw new RestClientException("Downstream service returned " + response.getStatusCode());
                }
                return response;
            }, isSafeForAutomaticRetry(method));
        } catch (RestClientException exception) {
            throw new GatewayUnavailableException("Downstream service is unavailable", exception);
        }
    }

    static boolean isSafeForAutomaticRetry(HttpMethod method) {
        return method == HttpMethod.GET
                || method == HttpMethod.HEAD
                || method == HttpMethod.OPTIONS;
    }

    private URI targetUri(String baseUrl, HttpServletRequest sourceRequest) {
        String requestPath = sourceRequest.getRequestURI();
        String path = requestPath.startsWith("/api/v1")
                ? requestPath.substring("/api/v1".length())
                : requestPath.substring("/api".length());
        String query = sourceRequest.getQueryString();
        String target = baseUrl + path + (query == null ? "" : "?" + query);
        return URI.create(target);
    }

    private void copyRequestHeaders(HttpServletRequest sourceRequest, HttpHeaders targetHeaders) {
        Enumeration<String> headerNames = sourceRequest.getHeaderNames();
        if (headerNames == null) {
            return;
        }

        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                continue;
            }
            Enumeration<String> values = sourceRequest.getHeaders(headerName);
            while (values.hasMoreElements()) {
                targetHeaders.add(headerName, values.nextElement());
            }
        }
        targetHeaders.set(
                CorrelationIdContext.HEADER_NAME,
                CorrelationIdContext.currentOrNew()
        );
        targetHeaders.set(
                CorrelationIdContext.TRACEPARENT_HEADER,
                CorrelationIdContext.childTraceparent()
        );
    }

    private ResponseEntity<byte[]> toResponse(
            HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        response.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, values);
            }
        });
        String traceparent = org.slf4j.MDC.get(CorrelationIdContext.MDC_TRACEPARENT);
        if (traceparent != null && headers.getFirst(CorrelationIdContext.TRACEPARENT_HEADER) == null) {
            headers.set(CorrelationIdContext.TRACEPARENT_HEADER, traceparent);
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers)
                .body(response.getBody().readAllBytes());
    }
}
