package org.demo.orderservice.promotion;

import org.demo.orderservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PromotionClient {
    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public PromotionClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.promotion.base-url}") String promotionServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(promotionServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public CouponValidationResponse validateCoupon(String code, BigDecimal subtotal) {
        if (code == null || code.isBlank()) return null;
        BigDecimal normalizedSubtotal = subtotal == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : subtotal.setScale(2, RoundingMode.HALF_UP);
        try {
            CouponValidationResponse response = resilientHttpExecutor.execute("promotion", () -> restClient.post()
                    .uri("/promotions/codes/validate")
                    .body(new CouponValidationRequest(code, normalizedSubtotal))
                    .retrieve()
                    .body(CouponValidationResponse.class));
            if (response == null) throw new PromotionServiceException("Promotion Service returned an empty response");
            if (!response.valid()) throw new InvalidPromotionCodeException(response.message());
            return response;
        } catch (InvalidPromotionCodeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new InvalidPromotionCodeException("Kod rabatowy jest nieprawidłowy.");
            }
            throw new PromotionServiceException("Promotion Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new PromotionServiceException("Could not reach Promotion Service", exception);
        }
    }
}
