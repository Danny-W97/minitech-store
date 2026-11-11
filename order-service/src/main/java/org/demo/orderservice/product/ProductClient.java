package org.demo.orderservice.product;

import org.demo.orderservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Component
public class ProductClient {

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public ProductClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.product.base-url}") String productServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(productServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public ProductResponse getProduct(Long productId) {
        return getProducts(List.of(productId)).get(productId);
    }

    public Map<Long, ProductResponse> getProducts(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty() || productIds.size() > 100
                || productIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Provide between 1 and 100 positive product IDs");
        }
        List<Long> ids = productIds.stream().distinct().toList();
        Set<Long> requested = Set.copyOf(ids);
        try {
            List<ProductResponse> products = resilientHttpExecutor.execute("product", () -> restClient.get()
                    .uri(builder -> builder.path("/internal/products/batch")
                            .queryParam("ids", ids.stream().map(String::valueOf).collect(Collectors.joining(","))).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProductResponse>>() {}));
            if (products == null) {
                throw new ProductServiceException("Product Service returned an empty response");
            }
            Map<Long, ProductResponse> indexed = new LinkedHashMap<>();
            for (ProductResponse product : products) {
                if (product == null || product.id() == null || !requested.contains(product.id())
                        || indexed.putIfAbsent(product.id(), product) != null
                        || product.price() == null || product.price().signum() < 0
                        || product.sku() == null || product.name() == null) {
                    throw new ProductServiceException("Product Service returned an invalid batch");
                }
            }
            for (Long id : ids) if (!indexed.containsKey(id)) throw new ProductNotFoundException(id);
            return indexed;
        } catch (RestClientResponseException exception) {
            throw new ProductServiceException("Product Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new ProductServiceException("Could not reach Product Service", exception);
        }
    }
}
