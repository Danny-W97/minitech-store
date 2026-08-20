package org.demo.productservice.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PromotionClient {
    private static final Logger log = LoggerFactory.getLogger(PromotionClient.class);
    private final RestClient client;

    public PromotionClient(@Value("${services.promotion.base-url:http://localhost:18089}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Map<Long, DailyCatalogRotation.ProductOffer> evaluate(List<Product> catalog) {
        if (catalog.isEmpty()) return Map.of();
        int size = catalog.size();
        List<PromotionEvaluationRequest> request = catalog.stream()
                .map(product -> new PromotionEvaluationRequest(product.getId(), product.getPrice(),
                        catalog.indexOf(product), size)).toList();
        try {
            List<PromotionEvaluationResponse> response = client.post()
                    .uri("/promotions/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return Map.of();
            return response.stream().collect(Collectors.toMap(PromotionEvaluationResponse::productId,
                    this::toOffer, (left, right) -> right));
        } catch (RestClientException exception) {
            log.warn("Promotion Service unavailable; using local catalog rotation fallback", exception);
            return Map.of();
        }
    }

    private DailyCatalogRotation.ProductOffer toOffer(PromotionEvaluationResponse response) {
        return new DailyCatalogRotation.ProductOffer(response.price(), response.regularPrice(),
                response.discountPercent(), response.promotional(), response.newProduct(), response.rotationDate());
    }

    private record PromotionEvaluationRequest(Long productId, java.math.BigDecimal basePrice,
                                              int catalogIndex, int catalogSize) {}

    private record PromotionEvaluationResponse(Long productId, java.math.BigDecimal price,
                                               java.math.BigDecimal regularPrice, int discountPercent,
                                               boolean promotional, boolean newProduct, LocalDate rotationDate,
                                               String campaignCode) {}
}
