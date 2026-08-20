package org.demo.paymentservice.inventory;

import org.demo.paymentservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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

    public void confirm(Long reservationId) {
        if (reservationId == null) {
            return;
        }
        try {
            resilientHttpExecutor.execute("inventory", () -> restClient.post()
                    .uri("/inventory/reservations/{id}/confirm", reservationId)
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientResponseException exception) {
            throw new InventoryServiceException("Inventory Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new InventoryServiceException("Could not reach Inventory Service", exception);
        }
    }

    /**
     * Kept as a compatibility entry point for the payment flow. Retry policy
     * is now centralized in Resilience4j and the legacy arguments are ignored.
     */
    public void confirmWithRetry(Long reservationId, int maxAttempts, long backoffMs) {
        confirm(reservationId);
    }

    public void release(Long reservationId) {
        if (reservationId == null) {
            return;
        }
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
