package org.demo.recommendationservice.export;

import org.demo.recommendationservice.telemetry.RecommendationLogProperties;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RecommendationDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("recommendation_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource source;
    private static RecommendationDataExportService service;
    @BeforeAll static void database() {
        source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);service=new RecommendationDataExportService(source,properties(false));
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE recommendation_requests RESTART IDENTITY");}
    private static RecommendationLogProperties properties(boolean enabled) {
        return new RecommendationLogProperties(enabled,false,Duration.ofDays(7),500,4,600000,60000);
    }
    @Test void pagesAreBoundedAndExcludeOtherAccountsAndAnonymousRows() {
        jdbc.update("INSERT INTO recommendation_requests(customer_id,source_product_id,strategy,result_count,requested_at) SELECT 17,n,'CUSTOMER_HISTORY',4,now() FROM generate_series(1,205) n");
        insert(18,999L);insert(null,999L);
        List<Long> products=new ArrayList<>();long cursor=0;int pages=0;
        while(true){
            var page=service.export(17,cursor,100);assertThat(page.items()).hasSizeLessThanOrEqualTo(100);
            page.items().forEach(row->products.add(row.sourceProductId()));pages++;
            if(!page.hasMore()){assertThat(page.nextCursor()).isEqualTo("0");break;}
            cursor=Long.parseLong(page.nextCursor());assertThat(cursor).isPositive();
        }
        assertThat(pages).isEqualTo(3);assertThat(products).hasSize(205).startsWith(205L,204L).endsWith(2L,1L);
        assertThat(new HashSet<>(products)).hasSize(205);assertThat(products).doesNotContain(999L);
        assertThat(service.export(18,0,100).items()).hasSize(1);
    }
    @Test void disabledRecordingStillExportsRetainedRowsAndPreservesNullableProductId() {
        insert(17,null);insert(null,123L);
        var page=service.export(17,0,10);
        assertThat(page.recordingEnabled()).isFalse();assertThat(page.complete()).isFalse();
        assertThat(page.historyCoverage()).isEqualTo("optional-retained-log");
        assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().sourceProductId()).isNull();
        assertThat(page.omissions()).contains(new RecommendationDataExportPage.Omission("anonymousRequests","not-account-linked"));
    }
    @Test void enabledFlagIsInformationalAndDoesNotChangeTheProjectionOrCreateEvents() {
        insert(17,123L);
        var disabled=service.export(17,0,10);
        var enabled=new RecommendationDataExportService(source,properties(true)).export(17,0,10);
        assertThat(enabled.recordingEnabled()).isTrue();assertThat(enabled.items()).isEqualTo(disabled.items());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_requests",Integer.class)).isEqualTo(1);
    }
    @Test void emptyOrPurgedLogNeverClaimsCompleteActivityHistory() {
        var empty=service.export(17,0,10);assertThat(empty.items()).isEmpty();assertThat(empty.complete()).isFalse();
        insert(17,123L);jdbc.update("DELETE FROM recommendation_requests WHERE customer_id=17");
        var purged=service.export(17,0,10);assertThat(purged.items()).isEmpty();assertThat(purged.hasMore()).isFalse();
        assertThat(purged.nextCursor()).isEqualTo("0");
        assertThat(purged.omissions()).contains(new RecommendationDataExportPage.Omission("unloggedOrPurgedRequests","not-available"));
    }
    @Test void changesBetweenPagesAreExplicitlyNotASnapshot() {
        insert(17,1L);insert(17,2L);insert(17,3L);
        var first=service.export(17,0,1);insert(17,4L);
        jdbc.update("DELETE FROM recommendation_requests WHERE customer_id=17 AND source_product_id=2");
        var second=service.export(17,Long.parseLong(first.nextCursor()),100);
        assertThat(second.items()).extracting(RecommendationDataExportPage.Item::sourceProductId).containsExactly(1L);
        assertThat(second.omissions()).contains(new RecommendationDataExportPage.Omission("changesDuringPagination","not-a-snapshot"));
    }
    @Test void invalidIdentityAndWindowsAreRejectedBeforeQuery() {
        for(int size:new int[]{-1,0,101,Integer.MAX_VALUE})assertStatus(400,()->service.export(17,0,size));
        assertStatus(400,()->service.export(17,-1,1));assertStatus(401,()->service.export(0,0,1));
        assertStatus(401,()->service.export(-1,0,1));assertThat(service.export(17,1,1).items()).isEmpty();
    }
    @Test void selectOnlyRoleCanExportWithoutWritesAndNewIndexIsPresent() {
        insert(17,123L);insert(null,456L);
        String before=jdbc.queryForObject("SELECT json_agg(r ORDER BY id)::text FROM recommendation_requests r",String.class);
        jdbc.execute("CREATE ROLE a43_recommendation_export LOGIN PASSWORD 'fixture-only-projection' NOSUPERUSER");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO a43_recommendation_export");
        jdbc.execute("GRANT SELECT ON recommendation_requests TO a43_recommendation_export");
        var restrictedSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"a43_recommendation_export","fixture-only-projection");
        assertThat(new RecommendationDataExportService(restrictedSource,properties(false)).export(17,0,100).items()).hasSize(1);
        assertThatThrownBy(()->new JdbcTemplate(restrictedSource).update("DELETE FROM recommendation_requests"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT json_agg(r ORDER BY id)::text FROM recommendation_requests r",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_recommendation_requests_owner_export'",Integer.class)).isEqualTo(1);
    }
    private void insert(Integer owner,Long product){jdbc.update("INSERT INTO recommendation_requests(customer_id,source_product_id,strategy,result_count,requested_at) VALUES (?,?,'CUSTOMER_HISTORY',4,now())",owner,product);}
    private void assertStatus(int status,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(status));}
}
