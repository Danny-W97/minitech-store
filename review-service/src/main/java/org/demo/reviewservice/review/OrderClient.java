package org.demo.reviewservice.review;

import org.demo.reviewservice.web.ReviewException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class OrderClient {
    private final RestClient restClient;

    public OrderClient(RestClient.Builder builder,
                       @Value("${services.order.base-url:http://localhost:18082}") String orderServiceBaseUrl) {
        this.restClient = builder.baseUrl(orderServiceBaseUrl).build();
    }

    public OrderSnapshot getOrder(Long orderId) {
        try {
            OrderSnapshot order = restClient.get().uri("/orders/{id}", orderId)
                    .retrieve().body(OrderSnapshot.class);
            if (order == null) throw new ReviewException(HttpStatus.SERVICE_UNAVAILABLE, "Order Service zwrócił pustą odpowiedź.");
            return order;
        } catch (ReviewException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ReviewException(HttpStatus.BAD_REQUEST, "Nie znaleziono wskazanego zamówienia.");
            }
            throw new ReviewException(HttpStatus.SERVICE_UNAVAILABLE, "Nie można zweryfikować zakupu w Order Service.");
        } catch (RestClientException exception) {
            throw new ReviewException(HttpStatus.SERVICE_UNAVAILABLE, "Order Service jest niedostępny.");
        }
    }

    public record OrderSnapshot(Long id, List<OrderItemSnapshot> items, String status, Long customerId) {}
    public record OrderItemSnapshot(Long productId) {}
}
