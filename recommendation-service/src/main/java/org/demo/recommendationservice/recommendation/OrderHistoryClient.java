package org.demo.recommendationservice.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    private static final ParameterizedTypeReference<List<OrderSnapshot>> ORDERS = new ParameterizedTypeReference<>() {};
    private final RestClient restClient;

    public OrderHistoryClient(RestClient.Builder builder,
                              @Value("${services.order.base-url:http://localhost:18082}") String orderServiceBaseUrl) {
        this.restClient = builder.baseUrl(orderServiceBaseUrl).build();
    }

    public List<OrderSnapshot> getCustomerOrders(Long customerId) {
        try {
            List<OrderSnapshot> orders = restClient.get()
                    .uri("/orders/customer/{customerId}", customerId)
                    .retrieve().body(ORDERS);
            return orders == null ? List.of() : orders;
        } catch (RestClientException exception) {
            throw new RecommendationException(HttpStatus.SERVICE_UNAVAILABLE, "Order Service jest niedostępny.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderSnapshot(String status, List<OrderItemSnapshot> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItemSnapshot(Long productId) {}
}
