package org.demo.productservice.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.AbstractList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PromotionClientTest {
    MockRestServiceServer server;
    PromotionClient client;

    @BeforeEach void prepare() {
        var builder = RestClient.builder().baseUrl("http://promotion");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PromotionClient(builder.build());
    }

    @Test void buildsPayloadLinearlyAndPreservesGlobalPositionsOfSubset() {
        var entries = List.of(new CatalogProduct(ProductOfferTest.product(99), 98, 1000), new CatalogProduct(ProductOfferTest.product(3), 2, 1000));
        var selected = new AbstractList<CatalogProduct>() {
            @Override public CatalogProduct get(int index) { return entries.get(index); }
            @Override public int size() { return entries.size(); }
            @Override public int indexOf(Object value) { throw new AssertionError("Linear indexOf must not be called per product"); }
        };
        server.expect(requestTo("http://promotion/promotions/evaluate"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(99))
                .andExpect(jsonPath("$[0].catalogIndex").value(98))
                .andExpect(jsonPath("$[1].catalogIndex").value(2))
                .andExpect(jsonPath("$[1].catalogSize").value(1000))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.evaluate(selected)).isEmpty(); server.verify();
    }

    @Test void serviceFailureReturnsEmptyMapForLazyLocalFallback() {
        server.expect(requestTo("http://promotion/promotions/evaluate")).andRespond(withServerError());
        assertThat(client.evaluate(List.of(new CatalogProduct(ProductOfferTest.product(3), 2, 1000)))).isEmpty();
        server.verify();
    }

    @Test void emptySelectionDoesNotMakeHttpRequest() {
        assertThat(client.evaluate(List.of())).isEmpty(); server.verify();
    }
}
