package org.demo.paymentservice.promotion;

import org.demo.paymentservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PromotionClient {

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public PromotionClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.promotion.base-url:http://localhost:18089}") String promotionServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(promotionServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public void redeemCoupon(String code, Long orderId) {
        if (code == null || code.isBlank() || orderId == null) {
            return;
        }
        try {
            resilientHttpExecutor.execute("promotion", () -> restClient.post()
                    .uri("/promotions/codes/redeem")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PromotionRedemptionRequest(code, orderId))
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientResponseException exception) {
            throw new PromotionServiceException(
                    "Promotion Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new PromotionServiceException("Could not reach Promotion Service", exception);
        }
    }
}
