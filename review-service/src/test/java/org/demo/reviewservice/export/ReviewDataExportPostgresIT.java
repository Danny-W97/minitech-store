package org.demo.reviewservice.export;

import org.demo.reviewservice.review.ReviewStatus;
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
class ReviewDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("review_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static JdbcTemplate jdbc;
    private static ReviewDataExportService service;

    @BeforeAll static void database() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); service = new ReviewDataExportService(source);
    }
    @BeforeEach void reset() {jdbc.execute("TRUNCATE reviews RESTART IDENTITY");}

    @Test void allOwnStatusesArePagedWithoutAnotherCustomersRows() {
        jdbc.update("""
                INSERT INTO reviews(customer_id,order_id,product_id,rating,title,content,status,moderation_note,created_at,updated_at)
                SELECT 17,n,n,5,'Tytuł '||n,'Treść '||n,
                  CASE n%3 WHEN 0 THEN 'PENDING' WHEN 1 THEN 'PUBLISHED' ELSE 'REJECTED' END,
                  'private-moderation-sentinel',now(),now() FROM generate_series(1,105) n
                """);
        insert(18, 1001, "PUBLISHED");
        List<Long> products = new ArrayList<>(); var statuses = new HashSet<ReviewStatus>();
        long cursor = 0; int pages = 0;
        while (true) {
            var page = service.export(17, cursor, 50);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(50); assertThat(page.complete()).isFalse();
            page.items().forEach(row -> {products.add(row.productId()); statuses.add(row.status());}); pages++;
            if (!page.hasMore()) {assertThat(page.nextCursor()).isEqualTo("0"); break;}
            cursor = Long.parseLong(page.nextCursor()); assertThat(cursor).isPositive();
        }
        assertThat(pages).isEqualTo(3); assertThat(products).hasSize(105).startsWith(105L,104L).endsWith(2L,1L);
        assertThat(new HashSet<>(products)).hasSize(105); assertThat(products).doesNotContain(1001L);
        assertThat(statuses).containsExactlyInAnyOrder(ReviewStatus.PENDING, ReviewStatus.PUBLISHED, ReviewStatus.REJECTED);
        assertThat(service.export(18,0,50).items()).extracting(ReviewDataExportPage.Item::productId).containsExactly(1001L);
    }

    @Test void projectionWorksWithoutDatabaseRightsOnModerationNotesAndOrderLinks() {
        insert(17, 123, "REJECTED");
        jdbc.execute("CREATE ROLE a43_review_projection LOGIN PASSWORD 'fixture-only-projection' NOSUPERUSER");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO a43_review_projection");
        jdbc.execute("GRANT SELECT (id,customer_id,product_id,rating,title,content,status,created_at,updated_at) ON reviews TO a43_review_projection");
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "a43_review_projection", "fixture-only-projection");
        var restricted = new JdbcTemplate(source);
        assertThatThrownBy(() -> restricted.queryForList("SELECT moderation_note FROM reviews")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> restricted.queryForList("SELECT order_id FROM reviews")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var page = new ReviewDataExportService(source).export(17, 0, 50);
        assertThat(page.items()).hasSize(1); assertThat(page.items().getFirst().status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(page.omissions()).contains(new ReviewDataExportPage.Omission("moderationNotes", "manual-review-and-redaction-procedure"),new ReviewDataExportPage.Omission("orderLinks", "excluded"));
    }

    @Test void userAuthoredUnicodeAndLiteralMarkupAreReturnedWithoutSemanticRedaction() {
        insert(17, 123, "PENDING");
        jdbc.update("UPDATE reviews SET title=?,content=? WHERE customer_id=17", "Zażółć gęślą", "<script>alert('literal')</script>\nKontakt: osoba@example.test");
        var page = service.export(17,0,1);
        assertThat(page.items().getFirst().title()).isEqualTo("Zażółć gęślą");
        assertThat(page.items().getFirst().content()).isEqualTo("<script>alert('literal')</script>\nKontakt: osoba@example.test");
        assertThat(page.omissions()).contains(new ReviewDataExportPage.Omission("userAuthoredText", "as-stored-no-semantic-redaction"));
    }

    @Test void emptyAndExactPageHaveNoContinuation() {
        assertThat(service.export(17,0,2).items()).isEmpty();
        insert(17,1,"PENDING"); insert(17,2,"PUBLISHED");
        var page = service.export(17,0,2);
        assertThat(page.hasMore()).isFalse(); assertThat(page.nextCursor()).isEqualTo("0");
    }

    @Test void moderationAndInsertBetweenPagesAreExplicitlyNotASnapshot() {
        insert(17,1,"PENDING"); insert(17,2,"PENDING"); insert(17,3,"PENDING");
        var first = service.export(17,0,1);
        insert(17,4,"PENDING"); jdbc.update("UPDATE reviews SET status='REJECTED',updated_at=now() WHERE product_id=2");
        var second = service.export(17,Long.parseLong(first.nextCursor()),50);
        assertThat(second.items()).extracting(ReviewDataExportPage.Item::productId).containsExactly(2L,1L);
        assertThat(second.items().getFirst().status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(second.omissions()).contains(new ReviewDataExportPage.Omission("changesDuringPagination","not-a-snapshot"));
    }

    @Test void invalidWindowsAndSubjectAreRejectedBeforeQuery() {
        for (int size : new int[]{-1,0,51,Integer.MAX_VALUE}) assertStatus(400,()->service.export(17,0,size));
        assertStatus(400,()->service.export(17,-1,2)); assertStatus(401,()->service.export(0,0,2));
        assertStatus(401,()->service.export(-1,0,2)); assertThat(service.export(17,1,2).items()).isEmpty();
    }

    @Test void exportDoesNotChangeRowsAndReusesExistingOwnerIndex() {
        insert(17,123,"PUBLISHED"); insert(18,456,"REJECTED");
        String before = jdbc.queryForObject("SELECT json_agg(r ORDER BY id)::text FROM reviews r",String.class);
        service.export(17,0,50); service.export(17,0,50);
        assertThat(jdbc.queryForObject("SELECT json_agg(r ORDER BY id)::text FROM reviews r",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='reviews' AND indexname='idx_reviews_customer_window'",Integer.class)).isEqualTo(1);
    }
    private void insert(long owner,long product,String status) {
        jdbc.update("""
                INSERT INTO reviews(customer_id,order_id,product_id,rating,title,content,status,moderation_note,created_at,updated_at)
                VALUES (?,?,?,5,'Tytuł','Treść',?,'private-moderation-sentinel',now(),now())
                """,owner,product,product,status);
    }
    private void assertStatus(int expected,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error->assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
