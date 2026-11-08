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
    public Long reservationForCancellation(Long orderId) {
        var row=lookupForReconciliation(orderId);
        return row==null ? null : row.reservationId();
    }
    public InventoryReservationResponse lookupForReconciliation(Long orderId) {
        try {
            var row=resilientHttpExecutor.execute("inventory",() -> restClient.get()
                    .uri("/inventory/reservations/order/{id}",orderId).retrieve().body(InventoryReservationResponse.class));
            if(row==null || !orderId.equals(row.orderId()) || row.reservationId()==null)
                throw new InventoryServiceException("Invalid inventory reservation identity",null);
            return row;
        } catch(RestClientResponseException failure) {
            if(failure.getStatusCode().value()==404) return null;
            throw new InventoryServiceException("Inventory cancellation lookup failed",failure);
        } catch(RestClientException failure) { throw new InventoryServiceException("Inventory cancellation lookup unavailable",failure); }
    }
    public void releaseForCancellation(Long orderId,Long reservationId) {
        try {
            var row=resilientHttpExecutor.execute("inventory",() -> restClient.post()
                    .uri("/inventory/reservations/{id}/release",reservationId).retrieve().body(InventoryReservationResponse.class));
            if(row==null || !orderId.equals(row.orderId()) || !reservationId.equals(row.reservationId()) || !"RELEASED".equals(row.status()))
                throw new InventoryServiceException("Inventory did not confirm this order's release",null);
        } catch(RestClientException failure) { throw new InventoryServiceException("Inventory cancellation release unavailable",failure); }
    }
}
