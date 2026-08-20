package org.demo.paymentservice.delivery;

import org.demo.paymentservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class DeliveryClient {

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public DeliveryClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.delivery.base-url:http://localhost:18088}") String deliveryServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(deliveryServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public void ensureTracking(Long orderId, String methodCode) {
        if (orderId == null) {
            return;
        }
        String resolvedMethod = methodCode == null || methodCode.isBlank()
                ? "STANDARD_COURIER" : methodCode;
        try {
            resilientHttpExecutor.execute("delivery", () -> restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/deliveries/orders/{orderId}/tracking")
                            .queryParam("methodCode", resolvedMethod)
                            .build(orderId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientResponseException exception) {
            throw new DeliveryServiceException(
                    "Delivery Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new DeliveryServiceException("Could not reach Delivery Service", exception);
        }
    }
}
