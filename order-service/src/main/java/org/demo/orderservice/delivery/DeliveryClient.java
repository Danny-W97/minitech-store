package org.demo.orderservice.delivery;

import org.demo.orderservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

@Component
public class DeliveryClient {
    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public DeliveryClient(
            RestClient.Builder builder,
            @Value("${services.delivery.base-url:http://localhost:18088}") String baseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public DeliveryQuoteResponse quote(String method, String postalCode, int itemCount,
                                       BigDecimal subtotal, LocalDate requestedDate) {
        return quote(method, postalCode, itemCount, subtotal, requestedDate, null);
    }

    public DeliveryQuoteResponse quote(String method, String postalCode, int itemCount,
                                       BigDecimal subtotal, LocalDate requestedDate, String pickupLocation) {
        String methodCode = method == null || method.isBlank()
                ? "STANDARD_COURIER" : method.trim().toUpperCase(Locale.ROOT);
        try {
            DeliveryQuoteResponse quote = resilientHttpExecutor.execute("delivery", () -> restClient.post()
                    .uri("/deliveries/quote")
                    .body(new QuoteRequest(methodCode, postalCode, itemCount, subtotal, requestedDate, pickupLocation))
                    .retrieve()
                    .body(DeliveryQuoteResponse.class));
            if (quote == null || !methodCode.equals(quote.methodCode())
                    || quote.deliveryCost() == null || quote.deliveryCost().signum() < 0
                    || quote.selectedDate() == null
                    || (requestedDate != null && !requestedDate.equals(quote.selectedDate()))
                    || !("STORE_PICKUP".equals(methodCode) ? "STORE_PICKUP" : "HOME_DELIVERY").equals(quote.fulfillmentType())
                    || ("STORE_PICKUP".equals(methodCode) && (quote.pickupLocationCode() == null || quote.pickupLocationCode().isBlank()
                    || quote.pickupLocation() == null || quote.pickupLocation().isBlank()))) {
                throw new DeliveryServiceException("Delivery Service returned an invalid quote");
            }
            return quote;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new InvalidDeliverySelectionException("Delivery method, requested date or pickup location is unavailable");
            }
            throw new DeliveryServiceException("Delivery Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new DeliveryServiceException("Could not reach Delivery Service", exception);
        }
    }

    private record QuoteRequest(String methodCode, String postalCode, int itemCount,
                                BigDecimal subtotal, LocalDate requestedDate, String pickupLocation) {
    }
}
