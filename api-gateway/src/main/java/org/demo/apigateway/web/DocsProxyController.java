package org.demo.apigateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kontroler agregujący dokumentację OpenAPI z mikroserwisów podrzędnych.
 * Przepisuje adresy serwerów na "/api/v1", aby interaktywne żądania ze Swagger UI
 * przechodziły przez API Gateway bez problemów z CORS.
 * Odpowiedzi są cachowane w pamięci podręcznej (TTL 60s) w celu maksymalnej wydajności.
 */
@RestController
@Hidden
public class DocsProxyController {

    private static final Logger log = LoggerFactory.getLogger(DocsProxyController.class);
    private static final long CACHE_TTL_SECONDS = 60;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> serviceUrls;
    private final Map<String, CachedDoc> docCache = new ConcurrentHashMap<>();

    private record CachedDoc(String content, Instant expiresAt) {}

    public DocsProxyController(
            RestClient.Builder restClientBuilder,
            @Value("${services.product.base-url:http://localhost:18081}") String productServiceBaseUrl,
            @Value("${services.order.base-url:http://localhost:18082}") String orderServiceBaseUrl,
            @Value("${services.inventory.base-url:http://localhost:18083}") String inventoryServiceBaseUrl,
            @Value("${services.payment.base-url:http://localhost:18084}") String paymentServiceBaseUrl,
            @Value("${services.notification.base-url:http://localhost:18086}") String notificationServiceBaseUrl,
            @Value("${services.customer.base-url:http://localhost:18087}") String customerServiceBaseUrl,
            @Value("${services.delivery.base-url:http://localhost:18088}") String deliveryServiceBaseUrl,
            @Value("${services.promotion.base-url:http://localhost:18089}") String promotionServiceBaseUrl,
            @Value("${services.wishlist.base-url:http://localhost:18090}") String wishlistServiceBaseUrl,
            @Value("${services.review.base-url:http://localhost:18091}") String reviewServiceBaseUrl,
            @Value("${services.recommendation.base-url:http://localhost:18092}") String recommendationServiceBaseUrl,
            @Value("${services.chat.base-url:http://localhost:18093}") String chatServiceBaseUrl,
            @Value("${services.returns.base-url:http://localhost:18094}") String returnsServiceBaseUrl,
            @Value("${services.billing.base-url:http://localhost:18095}") String billingServiceBaseUrl
    ) {
        this.restClient = restClientBuilder.build();
        this.serviceUrls = Map.ofEntries(
                Map.entry("product", productServiceBaseUrl),
                Map.entry("order", orderServiceBaseUrl),
                Map.entry("inventory", inventoryServiceBaseUrl),
                Map.entry("payment", paymentServiceBaseUrl),
                Map.entry("notification", notificationServiceBaseUrl),
                Map.entry("customer", customerServiceBaseUrl),
                Map.entry("delivery", deliveryServiceBaseUrl),
                Map.entry("promotion", promotionServiceBaseUrl),
                Map.entry("wishlist", wishlistServiceBaseUrl),
                Map.entry("review", reviewServiceBaseUrl),
                Map.entry("recommendation", recommendationServiceBaseUrl),
                Map.entry("chat", chatServiceBaseUrl),
                Map.entry("returns", returnsServiceBaseUrl),
                Map.entry("billing", billingServiceBaseUrl)
        );
    }

    @GetMapping(value = "/v3/api-docs/{serviceName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getServiceApiDocs(
            @PathVariable String serviceName,
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh
    ) {
        String baseUrl = serviceUrls.get(serviceName.toLowerCase());
        if (baseUrl == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\": \"Nieznany serwis: " + serviceName + "\"}");
        }

        Instant now = Instant.now();
        if (!refresh) {
            CachedDoc cached = docCache.get(serviceName.toLowerCase());
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                        .body(cached.content());
            }
        }

        try {
            String targetUrl = baseUrl.replaceAll("/+$", "") + "/v3/api-docs";
            log.info("Pobieram specyfikację OpenAPI dla serwisu {} z {}", serviceName, targetUrl);

            String rawJson = restClient.get()
                    .uri(targetUrl)
                    .retrieve()
                    .body(String.class);

            if (rawJson == null || rawJson.isBlank()) {
                throw new IllegalStateException("Pusta odpowiedź z serwisu " + serviceName);
            }

            JsonNode rootNode = objectMapper.readTree(rawJson);
            if (rootNode.isObject()) {
                ObjectNode objectNode = (ObjectNode) rootNode;
                ArrayNode serversNode = objectMapper.createArrayNode();
                serversNode.addObject()
                        .put("url", "/api/v1")
                        .put("description", "API Gateway v1 (Zalecane - przez bramę)");
                objectNode.set("servers", serversNode);
                rawJson = objectMapper.writeValueAsString(objectNode);
            }

            docCache.put(serviceName.toLowerCase(), new CachedDoc(rawJson, now.plusSeconds(CACHE_TTL_SECONDS)));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                    .body(rawJson);

        } catch (Exception e) {
            log.warn("Błąd pobierania dokumentacji OpenAPI dla {}: {}", serviceName, e.getMessage());
            CachedDoc stale = docCache.get(serviceName.toLowerCase());
            if (stale != null) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=10")
                        .body(stale.content());
            }
            try {
                String errorJson = objectMapper.writeValueAsString(Map.of(
                        "error", "Dokumentacja serwisu " + serviceName + " jest chwilowo niedostępna: " + e.getMessage()
                ));
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(errorJson);
            } catch (Exception jsonEx) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\": \"Dokumentacja serwisu jest chwilowo niedostępna\"}");
            }
        }
    }
}
