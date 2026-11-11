package org.demo.inventoryservice.inventory;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:stockbatch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true", "logging.level.org.hibernate.stat=OFF"})
class InventoryBatchReadTest {
    @Autowired InventoryItemRepository items;
    @Autowired InventoryService inventory;
    @Autowired EntityManagerFactory factory;
    Statistics statistics;

    @BeforeEach void seed() {
        items.deleteAllInBatch();
        items.saveAllAndFlush(LongStream.rangeClosed(1, 1000).mapToObj(id -> new InventoryItem(id, (int) (id % 20))).toList());
        statistics = factory.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
    }

    @Test void batchLoadsOnlyRequestedRowsInOneQueryAndPreservesOrder() {
        var result = inventory.getItems(List.of(999L, 2L, 999L, 5000L));
        assertThat(result).extracting(item -> item.productId()).containsExactly(999L, 2L);
        assertThat(result.getFirst().availableQuantity()).isEqualTo(19);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test void missingRowsAreOmittedWithoutInventingZeroAvailability() {
        assertThat(inventory.getItems(List.of(5000L))).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test void zeroStockAndReservationCountersMatchSingleRead() {
        var result = inventory.getItems(List.of(20L)).getFirst();
        assertThat(result.availableQuantity()).isZero();
        assertThat(result).isEqualTo(inventory.getItem(20L));
    }

    @Test void invalidAndOversizedBatchesDoNotReadDatabase() {
        for (var ids : List.of(List.<Long>of(), List.of(0L), List.of(-1L), java.util.Collections.nCopies(101, 1L))) {
            assertThatThrownBy(() -> inventory.getItems(ids)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }
}
