package org.demo.recommendationservice.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.recommendationservice.recommendation.RecommendationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.demo.recommendationservice.telemetry.RecommendationTelemetryTest.properties;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "recommendation.request-log.enabled=false", "recommendation.request-log.cleanup-enabled=false"})
@Testcontainers
class RecommendationLogPostgresIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("retention_fixture").withUsername("fixture").withPassword("retention-fixture-only");
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired RecommendationRequestRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    private RecommendationLogCleanup activeWorker;
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(604800);
    @BeforeEach void reset() {jdbc.execute("TRUNCATE TABLE recommendation_requests RESTART IDENTITY");}
    private void insert(Instant time, int count) {
        for(int i=0;i<count;i++) jdbc.update("INSERT INTO recommendation_requests(strategy,result_count,requested_at) VALUES ('PRODUCT_SIMILARITY',2,?)",Timestamp.from(time));
    }
    private long count() {return jdbc.queryForObject("SELECT count(*) FROM recommendation_requests",Long.class);}

    @Test
    void boundedDeletionPreservesRecentAndExactBoundaryRows() {
        insert(CUTOFF.minusSeconds(1),5);insert(CUTOFF,1);insert(NOW,2);
        assertThat(repository.deleteExpiredBatch(CUTOFF,2)).isEqualTo(2);
        assertThat(count()).isEqualTo(6);
        assertThat(repository.deleteExpiredBatch(CUTOFF,2)).isEqualTo(2);
        assertThat(repository.deleteExpiredBatch(CUTOFF,2)).isEqualTo(1);
        assertThat(repository.deleteExpiredBatch(CUTOFF,2)).isZero();
        assertThat(count()).isEqualTo(3);assertThat(repository.oldestRequestedAt()).isEqualTo(CUTOFF);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_recommendation_requests_retention'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_recommendation_requests_requested_at'",Integer.class)).isZero();
    }

    @Test
    void heldRowsAreSkippedAndBecomeEligibleAfterTheirTransactionEnds() throws Exception {
        insert(CUTOFF.minusSeconds(1),4);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.createStatement();var rows=statement.executeQuery("SELECT id FROM recommendation_requests ORDER BY requested_at,id LIMIT 1 FOR UPDATE")) {
                assertThat(rows.next()).isTrue();long id=rows.getLong(1);
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->repository.deleteExpiredBatch(CUTOFF,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);
                }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_requests WHERE id=?",Integer.class,id)).isEqualTo(1);
            }
            locked.rollback();
        }
        assertThat(repository.deleteExpiredBatch(CUTOFF,2)).isEqualTo(2);assertThat(count()).isZero();
    }

    @Test
    void parallelTransactionsDeleteDisjointBatchesAndLeaveFreshRows() throws Exception {
        insert(CUTOFF.minusSeconds(1),7);insert(NOW,1);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return repository.deleteExpiredBatch(CUTOFF,3);});
            var b=pool.submit(()->{start.await();return repository.deleteExpiredBatch(CUTOFF,3);});start.countDown();
            assertThat(a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS)).isEqualTo(6);
        }
        assertThat(count()).isEqualTo(2);assertThat(repository.deleteExpiredBatch(CUTOFF,3)).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void workerBudgetExposesTheRemainingBacklogAndRecoversOnTheNextRun() {
        insert(CUTOFF.minusSeconds(1),5);insert(NOW,1);
        var registry=new SimpleMeterRegistry();
        var worker=activeWorker=new RecommendationLogCleanup(repository,properties(false,true,2,2),registry,Clock.fixed(NOW,ZoneOffset.UTC));
        worker.cleanup();assertThat(count()).isEqualTo(2);
        assertThat(registry.get("recommendation.request.log.cleanup.pending").gauge().value()).isEqualTo(1);
        worker.cleanup();assertThat(count()).isEqualTo(1);
        assertThat(registry.get("recommendation.request.log.cleanup.pending").gauge().value()).isZero();
        assertThat(registry.get("recommendation.request.log.deleted").counter().count()).isEqualTo(5);
    }
}
