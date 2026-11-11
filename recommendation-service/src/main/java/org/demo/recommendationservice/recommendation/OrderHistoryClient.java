package org.demo.recommendationservice.recommendation;

import org.demo.recommendationservice.web.RecommendationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class OrderHistoryClient {
    private final RestClient restClient;

    public OrderHistoryClient(RestClient.Builder builder,
                              @Value("${services.order.base-url:http://localhost:18082}") String orderServiceBaseUrl) {
        this.restClient = builder.baseUrl(orderServiceBaseUrl).build();
    }

    public java.util.Set<Long> purchasedProducts(Long customerId, List<Long> productIds) {
        var purchased = new java.util.HashSet<Long>();
        try {
            for (int offset = 0; offset < productIds.size(); offset += 100) {
                var batch = productIds.subList(offset, Math.min(offset + 100, productIds.size()));
                List<Long> result = restClient.get().uri(builder -> builder
                        .path("/internal/orders/customer/{id}/purchased-products")
                        .queryParam("productIds", batch.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
                        .build(customerId)).retrieve().body(new ParameterizedTypeReference<List<Long>>() {});
                if (result == null || !batch.containsAll(result)) throw new RecommendationException(HttpStatus.SERVICE_UNAVAILABLE, "Nieprawidłowa historia zakupów.");
                purchased.addAll(result);
            }
            return purchased;
        } catch (RestClientException exception) {
            throw new RecommendationException(HttpStatus.SERVICE_UNAVAILABLE, "Order Service jest niedostępny.");
        }
    }

}
