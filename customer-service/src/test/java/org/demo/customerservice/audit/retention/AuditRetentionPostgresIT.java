package org.demo.customerservice.audit.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.customerservice.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.demo.customerservice.audit.retention.AuditRetentionProperties.Mode.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditRetentionRepository.class,AuditService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class AuditRetentionPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_retention_fixture").withUsername("fixture").withPassword("audit-retention-fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired AuditRetentionRepository retention;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    private Instant now,cutoff,old;
    private AuditRetentionWorker activeWorker;
    @BeforeEach void setup() {
        removeRejection();jdbc.execute("TRUNCATE audit_events RESTART IDENTITY");
        now=Instant.now().truncatedTo(ChronoUnit.MICROS);cutoff=now.minus(Duration.ofDays(90));old=cutoff.minusSeconds(60);
    }
    private long event(Instant at,String type) {
        return jdbc.queryForObject("INSERT INTO audit_events(occurred_at,event_type,actor,resource,outcome,correlation_id,client_ip,user_agent,metadata) "
                        + "VALUES (?,?,?,'CUSTOMER','SUCCESS','fixture-correlation','127.0.0.1','fixture-agent','fixture metadata') RETURNING id",
                Long.class,Timestamp.from(at),type,"fixture@example.test");
    }
    private long count() {return jdbc.queryForObject("SELECT count(*) FROM audit_events",Long.class);}
    private AuditRetentionWorker worker(AuditRetentionProperties.Mode mode,int maxBatches,SimpleMeterRegistry registry) {
        activeWorker=new AuditRetentionWorker(retention,AuditRetentionWorkerTest.properties(mode,maxBatches),registry,Clock.fixed(now,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(SimpleMeterRegistry registry,String name) {return registry.get("customer.audit.retention."+name).gauge().value();}
    private void rejectFixtureEvents() {
        jdbc.execute("CREATE FUNCTION audit_retention_fixture_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF OLD.event_type='RETENTION_FIXTURE_BLOCK' THEN RAISE EXCEPTION 'fixture deletion rejected'; END IF; RETURN OLD; END $$");
        jdbc.execute("CREATE TRIGGER audit_retention_fixture_reject BEFORE DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION audit_retention_fixture_reject()");
    }
    private void removeRejection() {
        jdbc.execute("DROP TRIGGER IF EXISTS audit_retention_fixture_reject ON audit_events");
        jdbc.execute("DROP FUNCTION IF EXISTS audit_retention_fixture_reject()");
    }

    @Test void strictCutoffAndOrderedBatchesPreserveBoundaryRecentFutureAndAllTheirFields() {
        long a=event(old,"OLD"),b=event(old,"OLD"),boundary=event(cutoff,"BOUNDARY");
        long fresh=event(now,"FRESH"),future=event(now.plusSeconds(300),"FUTURE");
        var before=jdbc.queryForList("SELECT * FROM audit_events WHERE id IN (?,?,?) ORDER BY id",boundary,fresh,future);
        assertThat(retention.deleteBatch(cutoff,1)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM audit_events ORDER BY id",Long.class)).doesNotContain(a).contains(b);
        assertThat(retention.deleteBatch(cutoff,100)).isEqualTo(1);assertThat(retention.deleteBatch(cutoff,100)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM audit_events ORDER BY id")).isEqualTo(before);
        assertThat(retention.oldestEligible(cutoff)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_audit_events_retention'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_audit_events_occurred_at'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_audit_events_actor','idx_audit_events_event_type','idx_audit_events_correlation_id')",Integer.class)).isEqualTo(3);
    }
    @Test void observeKeepsOldEvidenceAndDoesNotInterfereWithActualAuditWrites() {
        event(old,"OLD_EVIDENCE");audit.record("FIXTURE_LOGIN","fixture@example.test","CUSTOMER","SUCCESS","fixture metadata");
        var registry=new SimpleMeterRegistry();worker(OBSERVE,4,registry).cleanup();
        assertThat(count()).isEqualTo(2);assertThat(gauge(registry,"purge.enabled")).isZero();
        assertThat(gauge(registry,"pending")).isEqualTo(1);assertThat(gauge(registry,"last.deleted")).isZero();
        assertThat(registry.get("customer.audit.retention.deleted").counter().count()).isZero();
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM audit_events WHERE event_type='FIXTURE_LOGIN'",Timestamp.class).toInstant()).isAfter(cutoff);
    }
    @Test void lockedRowsAreSkippedButStayVisibleUntilTheNextRun() throws Exception {
        long lockedId=event(old,"LOCKED");event(old,"OLD");event(old,"OLD");
        var registry=new SimpleMeterRegistry();var worker=worker(PURGE,1,registry);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM audit_events WHERE id=? FOR UPDATE")) {
                statement.setLong(1,lockedId);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->retention.deleteBatch(cutoff,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);
                    pool.submit(worker::cleanup).get(5,TimeUnit.SECONDS);
                }
                assertThat(count()).isEqualTo(1);assertThat(gauge(registry,"pending")).isEqualTo(1);
                assertThat(gauge(registry,"last.deleted")).isZero();
                audit.record("FIXTURE_DURING_LOCK","fixture@example.test","CUSTOMER","SUCCESS","fixture metadata");
            }
            locked.rollback();
        }
        worker.cleanup();assertThat(count()).isEqualTo(1);assertThat(gauge(registry,"pending")).isZero();
        assertThat(registry.get("customer.audit.retention.deleted").counter().count()).isEqualTo(1);
    }
    @Test void parallelBatchesAreDisjointAndTheWorkerDrainsOnlyItsBudget() throws Exception {
        for(int i=0;i<10;i++)event(old,"OLD");event(cutoff,"BOUNDARY");event(now,"FRESH");
        int deleted;var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return retention.deleteBatch(cutoff,3);});
            var b=pool.submit(()->{start.await();return retention.deleteBatch(cutoff,3);});start.countDown();
            deleted=a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS);
        }
        assertThat(deleted).isBetween(3,6);assertThat(count()).isEqualTo(12-deleted);
        var registry=new SimpleMeterRegistry();var worker=worker(PURGE,1,registry);worker.cleanup();
        assertThat(gauge(registry,"last.deleted")).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(1);
        for(int i=0;i<5;i++)worker.cleanup();
        assertThat(count()).isEqualTo(2);assertThat(gauge(registry,"pending")).isZero();
        assertThat(registry.get("customer.audit.retention.deleted").counter().count()).isEqualTo(10-deleted);
    }
    @Test void aRejectedDeleteRollsBackTheEntireBatchAndCanRecover() {
        event(old,"OLD");event(old,"RETENTION_FIXTURE_BLOCK");rejectFixtureEvents();
        var registry=new SimpleMeterRegistry();var worker=worker(PURGE,1,registry);worker.cleanup();
        assertThat(count()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(-1);
        assertThat(registry.get("customer.audit.retention.deleted").counter().count()).isZero();
        assertThat(registry.get("customer.audit.retention.runs").tag("outcome","failed").counter().count()).isEqualTo(1);
        removeRejection();worker.cleanup();assertThat(count()).isZero();assertThat(gauge(registry,"pending")).isZero();
    }
    @Test void aLaterBatchFailureDoesNotLoseEarlierCommittedDeletionCounts() {
        for(String type:List.of("OLD","OLD","RETENTION_FIXTURE_BLOCK","OLD"))event(old,type);
        event(now,"FRESH");rejectFixtureEvents();
        var registry=new SimpleMeterRegistry();var worker=worker(PURGE,4,registry);worker.cleanup();
        assertThat(count()).isEqualTo(3);assertThat(registry.get("customer.audit.retention.deleted").counter().count()).isEqualTo(2);
        assertThat(gauge(registry,"last.success.timestamp.seconds")).isEqualTo(-1);
        removeRejection();worker.cleanup();
        assertThat(count()).isEqualTo(1);assertThat(registry.get("customer.audit.retention.deleted").counter().count()).isEqualTo(4);
        assertThat(gauge(registry,"last.deleted")).isEqualTo(2);assertThat(gauge(registry,"pending")).isZero();
    }
}
