package org.demo.wishlistservice.export;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class WishlistDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wishlist_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static JdbcTemplate jdbc;
    private static WishlistDataExportService service;

    @BeforeAll static void setupDatabase() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        service = new WishlistDataExportService(source);
    }

    @BeforeEach void reset() { jdbc.execute("TRUNCATE wishlist_items RESTART IDENTITY"); }

    @Test void keysetPagesAreBoundedStableAndRestrictedToOneOwner() {
        jdbc.update("INSERT INTO wishlist_items(customer_id,product_id,created_at) SELECT 17,n,CURRENT_TIMESTAMP FROM generate_series(1,205) n");
        jdbc.update("INSERT INTO wishlist_items(customer_id,product_id,created_at) SELECT 18,1000+n,CURRENT_TIMESTAMP FROM generate_series(1,15) n");
        List<Long> products = new ArrayList<>();
        long cursor = 0;
        int pages = 0;
        while (true) {
            var page = service.export(17, cursor, 100);
            assertThat(page.schemaVersion()).isEqualTo(1);
            assertThat(page.scope()).isEqualTo("wishlist-page");
            assertThat(page.complete()).isFalse();
            assertThat(page.items()).hasSizeLessThanOrEqualTo(100);
            assertThat(page.omissions()).extracting(WishlistDataExportPage.Omission::scope)
                    .containsExactly("otherAccountData", "changesDuringPagination");
            page.items().forEach(item -> {products.add(item.productId()); assertThat(item.createdAt()).isNotNull();});
            pages++;
            if (!page.hasMore()) { assertThat(page.nextCursor()).isEqualTo("0"); break; }
            assertThat(Long.parseLong(page.nextCursor())).isPositive();
            cursor = Long.parseLong(page.nextCursor());
        }
        assertThat(pages).isEqualTo(3);
        assertThat(products).hasSize(205).startsWith(205L, 204L).endsWith(2L, 1L);
        assertThat(new HashSet<>(products)).hasSize(205);
        assertThat(products).doesNotContain(1001L, 1015L);
        assertThat(service.export(18, 0, 100).items()).hasSize(15);
    }

    @Test void emptyAndExactPageDoNotInventAContinuation() {
        assertThat(service.export(17, 0, 2).items()).isEmpty();
        insert(17, 1); insert(17, 2);
        var page = service.export(17, 0, 2);
        assertThat(page.hasMore()).isFalse(); assertThat(page.nextCursor()).isEqualTo("0");
        assertThat(page.items()).extracting(WishlistDataExportPage.Item::productId).containsExactly(2L, 1L);
    }

    @Test void concurrentChangesAreNotPresentedAsAConsistentSnapshot() {
        insert(17, 1); insert(17, 2); insert(17, 3);
        var first = service.export(17, 0, 1);
        insert(17, 4);
        jdbc.update("DELETE FROM wishlist_items WHERE customer_id=17 AND product_id=2");
        var second = service.export(17, Long.parseLong(first.nextCursor()), 10);
        assertThat(first.items()).extracting(WishlistDataExportPage.Item::productId).containsExactly(3L);
        assertThat(second.items()).extracting(WishlistDataExportPage.Item::productId).containsExactly(1L);
        assertThat(second.omissions()).contains(new WishlistDataExportPage.Omission("changesDuringPagination", "not-a-snapshot"));
    }

    @Test void invalidWindowAndIdentityAreRejectedBeforeAnyQuery() {
        for (int size : new int[]{-1, 0, 101, Integer.MAX_VALUE})
            assertStatus(400, () -> service.export(17, 0, size));
        assertStatus(400, () -> service.export(17, -1, 2));
        assertStatus(401, () -> service.export(0, 0, 2));
        assertStatus(401, () -> service.export(-1, 0, 2));
        assertThat(service.export(17, 1, 2).items()).isEmpty();
    }

    @Test void exportDoesNotModifyRowsAndMigrationIndexIsPresent() {
        insert(17, 123); insert(18, 456);
        String before = jdbc.queryForObject("SELECT json_agg(w ORDER BY id)::text FROM wishlist_items w", String.class);
        service.export(17, 0, 10); service.export(17, 0, 10);
        assertThat(jdbc.queryForObject("SELECT json_agg(w ORDER BY id)::text FROM wishlist_items w", String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE tablename='wishlist_items' AND indexname='idx_wishlist_export_owner_id'", Integer.class)).isEqualTo(1);
    }

    private void insert(long customer, long product) {
        jdbc.update("INSERT INTO wishlist_items(customer_id, product_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)", customer, product);
    }
    private void assertStatus(int expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
