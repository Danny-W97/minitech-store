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
    public void confirmDeferred(Long orderId,Long reservationId) {
        if(reservationId==null) throw new InventoryServiceException("Deferred order has no inventory reservation");
        try {
            var result=resilientHttpExecutor.execute("inventory",() -> restClient.post()
                    .uri("/inventory/reservations/{id}/confirm",reservationId).retrieve().body(Confirmation.class));
            if(result==null || !orderId.equals(result.orderId()) || !reservationId.equals(result.reservationId()) || !"CONFIRMED".equals(result.status()))
                throw new InventoryServiceException("Inventory did not confirm this order's reservation");
        } catch(RestClientException error) { throw new InventoryServiceException("Could not confirm deferred inventory",error); }
    }
    public record Confirmation(Long reservationId,Long orderId,String status) {}

    public void extendForCheckout(Long orderId,Long reservationId,java.time.Instant expiresAt) {
        if(orderId==null || reservationId==null || expiresAt==null)
            throw new InventoryServiceException("Stripe Checkout requires an active order reservation");
        try {
            var result=resilientHttpExecutor.execute("inventory",() -> restClient.post()
                    .uri("/inventory/reservations/{id}/checkout-extension",reservationId)
                    .body(new CheckoutExtension(orderId,expiresAt)).retrieve().body(Confirmation.class));
            if(result==null || !orderId.equals(result.orderId()) || !reservationId.equals(result.reservationId()) || !"RESERVED".equals(result.status()))
                throw new InventoryServiceException("Inventory did not extend this order's reservation");
        } catch(RestClientException error) { throw new InventoryServiceException("Could not extend inventory for Stripe Checkout",error); }
    }
    public record CheckoutExtension(Long orderId,java.time.Instant expiresAt) {}
    public void compensateStripe(Long orderId,Long reservationId) {
        if(reservationId==null) throw new InventoryServiceException("Stripe compensation requires a reservation identity");
        try {
            var result=resilientHttpExecutor.execute("inventory",() -> restClient.post()
                    .uri("/inventory/reservations/{id}/stripe-compensation",reservationId).body(java.util.Map.of("orderId",orderId))
                    .retrieve().body(Confirmation.class));
            if(result==null || !orderId.equals(result.orderId()) || !reservationId.equals(result.reservationId()) || !"RELEASED".equals(result.status()))
                throw new InventoryServiceException("Inventory did not confirm Stripe compensation");
        } catch(RestClientException error) { throw new InventoryServiceException("Stripe inventory compensation unavailable",error); }
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
