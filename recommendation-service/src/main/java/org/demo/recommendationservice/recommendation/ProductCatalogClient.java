package org.demo.recommendationservice.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.demo.recommendationservice.web.RecommendationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class ProductCatalogClient {
    private static final ParameterizedTypeReference<List<ProductSnapshot>> PRODUCTS = new ParameterizedTypeReference<>() {};
    private final RestClient restClient;

    public ProductCatalogClient(RestClient.Builder builder,
                                @Value("${services.product.base-url:http://localhost:18081}") String productServiceBaseUrl) {
        this.restClient = builder.baseUrl(productServiceBaseUrl).build();
    }

    public List<ProductSnapshot> getActiveProducts() {
        try {
            List<ProductSnapshot> products = restClient.get().uri("/products")
                    .retrieve().body(PRODUCTS);
            return products == null ? List.of() : products.stream().filter(ProductSnapshot::active).toList();
        } catch (RestClientException exception) {
            throw new RecommendationException(HttpStatus.SERVICE_UNAVAILABLE, "Product Service jest niedostępny.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductSnapshot(
            Long id,
            String name,
            String category,
            String manufacturer,
            String model,
            Map<String, Object> technicalParameters,
            BigDecimal price,
            BigDecimal regularPrice,
            int discountPercent,
            boolean promotional,
            boolean newProduct,
            boolean active,
            String imageUrl
    ) {}
}
