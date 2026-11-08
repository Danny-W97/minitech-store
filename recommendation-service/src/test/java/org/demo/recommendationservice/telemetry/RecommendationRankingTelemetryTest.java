package org.demo.recommendationservice.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.recommendationservice.recommendation.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.demo.recommendationservice.telemetry.RecommendationTelemetryTest.properties;

class RecommendationRankingTelemetryTest {
    private final ProductCatalogClient products = mock(ProductCatalogClient.class);
    private final OrderHistoryClient orders = mock(OrderHistoryClient.class);
    private final RecommendationRequestRepository repository = mock(RecommendationRequestRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RecommendationService service(boolean logging) {
        when(products.getActiveProducts()).thenReturn(List.of(product(1L,"RAM"), product(2L,"RAM"), product(3L,"GPU")));
        return new RecommendationService(products, orders, new RecommendationTelemetry(repository, properties(logging,true,500,4), registry));
    }
    private ProductCatalogClient.ProductSnapshot product(Long id, String category) {
        return new ProductCatalogClient.ProductSnapshot(id,"Product "+id,category,"Fixture","Model", Map.of(),
                BigDecimal.TEN,BigDecimal.TEN,0,false,false,true,"fixture-image-"+id);
    }

    @Test
    void productRankingAndImageFieldsArePreservedWithoutDatabaseLogging() {
        var result = service(false).forProduct(1L,2);
        assertThat(result).extracting(r -> r.productId()).containsExactly(2L,3L);
        assertThat(result.getFirst().imageUrl()).isEqualTo("fixture-image-2");
        verifyNoInteractions(repository,orders);
    }

    @Test
    void customerHistoryStillExcludesPurchasedProductsAndReportsAggregateResults() {
        var service = service(false);
        when(orders.purchasedProducts(eq(17L),anyList())).thenReturn(Set.of(1L));
        assertThat(service.forCustomer(17L,2)).extracting(r -> r.productId()).containsExactly(2L,3L);
        verifyNoInteractions(repository);
        assertThat(registry.get("recommendation.responses").tag("strategy","CUSTOMER_HISTORY").counter().count()).isEqualTo(1);
    }

    @Test
    void optionalDiagnosticStorageFailureDoesNotChangeTheResponse() {
        var service = service(true);
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("fixture"));
        assertThat(service.forProduct(1L,2)).hasSize(2);
        assertThat(registry.get("recommendation.request.log.writes").tag("outcome","failed").counter().count()).isEqualTo(1);
    }

    @Test
    void missingProductDoesNotRecordASuccessfulResponse() {
        var service = service(false);
        assertThatThrownBy(() -> service.forProduct(99L,2)).hasMessageContaining("Nie znaleziono");
        assertThat(registry.get("recommendation.responses").tag("strategy","PRODUCT_SIMILARITY").counter().count()).isZero();
        verifyNoInteractions(repository);
    }
}
