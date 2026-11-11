package org.demo.promotionservice.promotion;

import org.demo.promotionservice.web.PriceHistoryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class ProductPriceHistoryClient {
    private final RestClient client;

    public ProductPriceHistoryClient(RestClient.Builder builder,
            @Value("${services.product.base-url:${PRODUCT_SERVICE_URL:http://localhost:18081}}") String baseUrl) {
        client = builder.clone().baseUrl(baseUrl).build();
    }

    public List<PriceHistoryResponse> history(Long productId) {
        List<PriceHistoryResponse> response = client.get().uri("/internal/products/price-history/{id}?size=100", productId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return response == null ? List.of() : response;
    }

    public Summary lowest(Long productId, int days) {
        return client.get().uri("/internal/products/price-history/{id}/lowest?days={days}", productId, days)
                .retrieve().body(Summary.class);
    }

    public record Summary(Long productId, int days, LocalDate from, LocalDate to,
                          BigDecimal lowestObservedPrice, int observedDays, List<LocalDate> observedDates) {}
}
