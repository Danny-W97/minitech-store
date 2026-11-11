package org.demo.orderservice.product;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.demo.orderservice.config.ResilientHttpExecutor;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ProductClientTest {
    MockRestServiceServer server;
    ProductClient client;
    private static final String FIRST = "{\"id\":1,\"sku\":\"SKU-1\",\"name\":\"First\",\"price\":99.99,\"active\":true,\"discountPercent\":0,\"promotional\":false,\"newProduct\":false,\"imageUrl\":\"/unchanged.png\"}";
    private static final String SECOND = "{\"id\":2,\"sku\":\"SKU-2\",\"name\":\"Second\",\"price\":49.50,\"active\":true,\"discountPercent\":0,\"promotional\":false,\"newProduct\":false}";

    @BeforeEach void prepare() {
        var builder = RestClient.builder().requestInterceptor(new ServiceAuthenticationInterceptor("order-credential"));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProductClient(builder, "http://product", new ResilientHttpExecutor(CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()), BulkheadRegistry.ofDefaults()));
    }

    @Test void resolvesWholeCartInOneAuthenticatedRequestRegardlessOfResponseOrder() {
        server.expect(requestTo("http://product/internal/products/batch?ids=1,2"))
                .andExpect(header("Authorization", "Bearer order-credential"))
                .andRespond(withSuccess("[" + SECOND + "," + FIRST + "]", MediaType.APPLICATION_JSON));
        var result = client.getProducts(List.of(1L, 2L, 1L));
        assertThat(result).hasSize(2);
        assertThat(result.get(1L).price()).isEqualByComparingTo("99.99");
        server.verify();
    }

    @Test void identifiesMissingCartLineWithoutOneRequestPerItem() {
        server.expect(requestTo("http://product/internal/products/batch?ids=1,2"))
                .andRespond(withSuccess("[" + FIRST + "]", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getProducts(List.of(1L, 2L))).isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("2");
        server.verify();
    }

    @Test void rejectsDuplicateUnexpectedAndInvalidPriceResponses() {
        for (String body : List.of("[" + FIRST + "," + FIRST + "]", "[" + SECOND + "]", "[" + FIRST.replace("99.99", "-1") + "]")) {
            server.reset();
            server.expect(requestTo("http://product/internal/products/batch?ids=1"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.getProducts(List.of(1L))).isInstanceOf(ProductServiceException.class);
            server.verify();
        }
    }

    @Test void dependencyFailureDoesNotFallBackToUnauthenticatedIndividualReads() {
        server.expect(requestTo("http://product/internal/products/batch?ids=1,2"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.getProducts(List.of(1L, 2L))).isInstanceOf(ProductServiceException.class);
        server.verify();
    }
}
