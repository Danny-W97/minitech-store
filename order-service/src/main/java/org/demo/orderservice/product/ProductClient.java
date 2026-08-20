package org.demo.orderservice.product;

import org.demo.orderservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
        try {
            ProductResponse product = resilientHttpExecutor.execute("product", () -> restClient.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .body(ProductResponse.class));

            if (product == null) {
                throw new ProductServiceException("Product Service returned an empty response");
            }
            return product;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ProductNotFoundException(productId);
            }
            throw new ProductServiceException("Product Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new ProductServiceException("Could not reach Product Service", exception);
        }
    }
}
