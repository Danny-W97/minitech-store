package org.demo.orderservice.inventory;

import org.demo.orderservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class InventoryClient {

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public InventoryClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.inventory.base-url}") String inventoryServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(inventoryServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public InventoryReservationResponse reserve(Long orderId, List<InventoryReservationItemRequest> items) {
        try {
            return resilientHttpExecutor.execute("inventory", () -> restClient.post()
                    .uri("/inventory/reservations")
                    .body(new InventoryReservationRequest(orderId, items))
                    .retrieve()
                    .body(InventoryReservationResponse.class));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new InventoryConflictException("Inventory reservation was rejected");
            }
            throw new InventoryServiceException("Inventory Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new InventoryServiceException("Could not reach Inventory Service", exception);
        }
    }

    public void release(Long reservationId) {
        try {
            resilientHttpExecutor.execute("inventory", () -> restClient.post()
                    .uri("/inventory/reservations/{id}/release", reservationId)
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientResponseException exception) {
            throw new InventoryServiceException("Inventory Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new InventoryServiceException("Could not reach Inventory Service", exception);
        }
    }
}
