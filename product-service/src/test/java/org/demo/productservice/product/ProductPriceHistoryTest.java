package org.demo.productservice.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:historydb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@Transactional
class ProductPriceHistoryTest {
    @Autowired ProductRepository products;
    @Autowired ProductPriceHistoryRepository events;
    @Autowired ProductPriceHistoryStore history;
    @Autowired DailyCatalogRotation rotation;

    private Product product() {
        return products.saveAndFlush(new Product("HISTORY", "History", "TEST", "Brand", "Model", Map.of(),
                new BigDecimal("100.00"), true, null));
    }

    @Test void sameDayPriceChangesAndReturnToTheOriginalPriceKeepEveryRevision() {
        var product = product();
        var entry = new CatalogProduct(product, 0, 1, 1);
        history.recordChange(entry, "CREATE");
        product.update(null, null, null, null, null, null, new BigDecimal("50.00"), null, null);
        history.recordChange(entry, "PRICE_CHANGE");
        product.update(null, null, null, null, null, null, new BigDecimal("100.00"), null, null);
        history.recordChange(entry, "PRICE_CHANGE");
        assertThat(history.history(product.getId(), 100)).extracting(row -> row.priceRevision()).containsExactly(3L, 2L, 1L);
        assertThat(history.history(product.getId(), 100)).extracting(row -> row.regularPrice())
                .containsExactly(new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("100.00"));
        assertThat(history.lowest(product.getId(), 1).lowestObservedPrice()).isEqualByComparingTo("47.50");
    }

    @Test void dailySnapshotsNeedNoReadsAndDoNotDuplicateButGapsRemainVisible() {
        var product = product();
        var catalog = List.of(new CatalogProduct(product, 0, 1, 1));
        var today = LocalDate.now(ZoneOffset.UTC);
        for (int day = 0; day < 30; day++) if (day != 5) history.recordDailySnapshot(catalog, today.minusDays(day));
        assertThat(history.lowest(product.getId(), 30, today).observedDays()).isEqualTo(29);
        assertThat(history.recordDailySnapshot(catalog, today)).isZero();
        assertThat(history.recordDailySnapshot(catalog, today.minusDays(5))).isEqualTo(1);
        assertThat(history.lowest(product.getId(), 30, today).observedDays()).isEqualTo(30);
        assertThat(events.count()).isEqualTo(30);
    }

    @Test void baselineAndPriceChangesCannotFillAMissingDailySnapshot() {
        var product = product();
        var entry = new CatalogProduct(product, 0, 1, 1);
        history.recordChange(entry, "BASELINE");
        assertThat(history.lowest(product.getId(), 1).lowestObservedPrice()).isNull();
        history.recordChange(entry, "PRICE_CHANGE");
        assertThat(history.lowest(product.getId(), 1).observedDays()).isZero();
        history.recordDailySnapshot(List.of(entry), LocalDate.now(ZoneOffset.UTC));
        assertThat(history.lowest(product.getId(), 1).observedDays()).isEqualTo(1);
    }

    @Test void aCatalogLayoutChangeRecordsOnlyAffectedEffectivePrices() {
        var product = product();
        var today = LocalDate.now(ZoneOffset.UTC);
        int index = java.util.stream.IntStream.range(0, 1000).filter(value ->
                rotation.offerFor(product, value, 1000, today).price().compareTo(
                        rotation.offerFor(product, value, 1001, today).price()) != 0).findFirst().orElseThrow();
        var previous = List.of(new CatalogProduct(product, index, 1000, 1));
        var current = List.of(new CatalogProduct(product, index, 1001, 2));
        assertThat(history.recordCatalogLayoutChange(previous, current)).isEqualTo(1);
        assertThat(history.recordCatalogLayoutChange(previous, current)).isZero();
        assertThat(history.history(product.getId(), 100).getFirst().layoutRevision()).isEqualTo(2);
        assertThat(history.lowest(product.getId(), 1).observedDays()).isZero();
        assertThat(history.recordCatalogLayoutChange(current, current)).isZero();
    }
}
