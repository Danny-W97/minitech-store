package org.demo.productservice.product;

import jakarta.persistence.EntityManagerFactory;
import org.demo.productservice.web.PatchProductRequest;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
class ProductCatalogQueryTest {
    @Autowired ProductRepository repository;
    @Autowired ProductService service;
    @Autowired EntityManagerFactory factory;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean PromotionClient promotions;
    List<Product> catalog;
    Statistics statistics;
    static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();

    @BeforeEach void seed() {
        repository.deleteAllInBatch();
        catalog = repository.saveAllAndFlush(IntStream.range(0, 1000).mapToObj(index -> new Product(
                "QUERY-" + index, "Product " + index, "TEST", "Brand", "Model", Map.of(),
                new BigDecimal("100.00"), index % 2 == 0, "/unchanged.png")).toList());
        // PostgreSQL uses V10 triggers; this H2 fixture supplies equivalent state.
        jdbc.execute("create table if not exists catalog_layout(id smallint primary key, revision bigint, product_count bigint)");
        jdbc.update("delete from catalog_layout");
        jdbc.update("insert into catalog_layout values(1, ?, 1000)", REVISION.incrementAndGet());
        when(promotions.evaluate(anyList())).thenReturn(Map.of());
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test void singleReadLoadsOnlyOneEntityAndPreservesFullCatalogOffer() {
        int index = 725;
        var selected = catalog.get(index);
        var expected = service.getAll().get(index);
        service.get(selected.getId()); // Populate only the ID index, never product data.
        clearInvocations(promotions); statistics.clear();
        var actual = service.get(selected.getId());
        assertThat(actual).isEqualTo(expected);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        verify(promotions).evaluate(argThat(entries -> entries.size() == 1
                && entries.getFirst().product().getId().equals(actual.id())
                && entries.getFirst().index() == index && entries.getFirst().size() == 1000));
    }

    @Test void batchLoadsOnlySelectedEntitiesAndReturnsRequestedOrderWithoutDuplicates() {
        service.get(catalog.getFirst().getId()); clearInvocations(promotions); statistics.clear();
        var ids = List.of(catalog.get(999).getId(), catalog.get(0).getId(), catalog.get(999).getId(), Long.MAX_VALUE);
        var actual = service.getBatch(ids);
        assertThat(actual).extracting(response -> response.id()).containsExactly(ids.get(0), ids.get(1));
        assertThat(statistics.getEntityLoadCount()).isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        verify(promotions).evaluate(argThat(entries -> entries.size() == 2
                && entries.get(0).index() == 999 && entries.get(1).index() == 0
                && entries.stream().allMatch(entry -> entry.size() == 1000)));
    }

    @Test void missingProductDoesNotReadCatalogOrContactPromotions() {
        assertThatThrownBy(() -> service.get(Long.MAX_VALUE)).isInstanceOf(ProductNotFoundException.class);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verifyNoInteractions(promotions);
    }

    @Test void missingBatchDoesNotEvaluatePromotionsOrLoadOtherProducts() {
        assertThat(service.getBatch(List.of(Long.MAX_VALUE))).isEmpty();
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verifyNoInteractions(promotions);
    }

    @Test void patchUsesCurrentPriceAndLeavesExistingFieldsIntact() {
        var id = catalog.get(50).getId();
        var actual = service.patch(id, new PatchProductRequest(null, null, null, null, null, null,
                new BigDecimal("199.99"), null, null));
        assertThat(actual.regularPrice()).isEqualByComparingTo("199.99");
        assertThat(actual.imageUrl()).isEqualTo("/unchanged.png");
        assertThat(service.get(id).price()).isEqualByComparingTo(actual.price());
    }

    @Test void promotionCallReleasesTheOnlyDatabaseConnection() {
        when(promotions.evaluate(anyList())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(repository.count()).isEqualTo(1000);
            return Map.of();
        });
        assertThat(service.get(catalog.getFirst().getId()).id()).isEqualTo(catalog.getFirst().getId());
    }
}
