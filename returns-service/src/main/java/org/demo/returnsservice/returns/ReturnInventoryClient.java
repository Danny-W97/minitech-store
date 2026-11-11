package org.demo.returnsservice.returns;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class ReturnInventoryClient {
    public record RestockRequest(Integer quantity, String operationId) {}
    public record RestockResponse(Long id, String operationId, Long productId, String operationType, Integer quantityDelta, Integer availableQuantity) {}

    private final RestClient client;

    public ReturnInventoryClient(RestClient.Builder builder,
                                 @Value("${services.inventory.base-url:${INVENTORY_SERVICE_URL:${PLATFORM_INVENTORY_URL:http://localhost:18083}}}") String base) {
        this.client = builder.baseUrl(base).build();
    }

    public RestockResponse restock(Long productId, int quantity, UUID operationId) {
        try {
            var body = new RestockRequest(quantity, operationId.toString());
            var response = client.post()
                    .uri("/inventory/items/{productId}/receipts", productId)
                    .body(body)
                    .retrieve()
                    .body(RestockResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVENTORY_RESTOCK_NO_RESPONSE");
            }
            return response;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(HttpStatus.valueOf(error.getStatusCode().value()),
                    "INVENTORY_RESTOCK_FAILED: " + error.getResponseBodyAsString());
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVENTORY_SERVICE_UNAVAILABLE");
        }
    }
}
