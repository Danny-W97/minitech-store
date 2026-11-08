package org.demo.billingservice.messaging.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.billingservice.messaging.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.demo.billingservice.messaging.retention.InvoiceOutboxRetentionProperties.Mode.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({InvoiceOutboxRetentionRepository.class,InvoiceOutboxStateStore.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class InvoiceOutboxRetentionPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("invoice_retention_fixture").withUsername("fixture").withPassword("invoice-retention-fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired InvoiceOutboxRetentionRepository retention;
    @Autowired InvoiceOutboxRepository events;
    @Autowired InvoiceOutboxStateStore states;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    private Instant now,cutoff,old;
    private int sequence;
    private InvoiceOutboxRetentionWorker activeWorker;
    @BeforeEach void setup() {
        removeRejection();jdbc.execute("TRUNCATE invoice_outbox_events RESTART IDENTITY");
        now=Instant.now().truncatedTo(ChronoUnit.MICROS);cutoff=now.minus(Duration.ofDays(30));old=cutoff.minusSeconds(60);sequence=0;
    }
    private Timestamp time(Instant value) {return value==null?null:Timestamp.from(value);}
    private long event(String status,Instant published,Instant created,String token,Instant lease) {
        var e=events.saveAndFlush(new InvoiceOutboxEvent("fixture-"+(++sequence),Map.of(
                "eventType","INVOICE_ISSUED","invoiceId","1","padding","fixture".repeat(800))));
        jdbc.update("UPDATE invoice_outbox_events SET status=?,published_at=?,created_at=?,processing_token=?,processing_until=?,"
                + "attempts=2,next_attempt_at=?,last_error='fixture diagnostic' WHERE id=?",
                status,time(published),time(created),token,time(lease),time(now.minusSeconds(1)),e.getId());
        return e.getId();
    }
    private long published() {return event("PUBLISHED",old,old,null,null);}
    private long compacted() {return jdbc.queryForObject("SELECT count(*) FROM invoice_outbox_events WHERE payload_compacted_at IS NOT NULL",Long.class);}
    private InvoiceOutboxRetentionWorker worker(InvoiceOutboxRetentionProperties.Mode mode,int maxBatches,SimpleMeterRegistry registry) {
        activeWorker=new InvoiceOutboxRetentionWorker(retention,InvoiceOutboxRetentionWorkerTest.properties(mode,maxBatches),registry,Clock.fixed(now,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(SimpleMeterRegistry r,String name) {return r.get("billing.outbox.retention."+name).gauge().value();}
    private void removeRejection() {
        jdbc.execute("DROP TRIGGER IF EXISTS invoice_retention_fixture_reject ON invoice_outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS invoice_retention_fixture_reject()");
    }

    @Test void onlyOldPublishedUnleasedPayloadsQualifyAndTheBoundaryIsStrict() {
        long first=published(),second=published();
        event("PENDING",old,old,null,null);event("FAILED",old,old,null,null);
        event("PUBLISHED",null,old,null,null);event("PUBLISHED",cutoff,old,null,null);
        event("PUBLISHED",old,cutoff,null,null);event("PUBLISHED",now,old,null,null);
        event("PUBLISHED",old,now.plusSeconds(60),null,null);
        event("PUBLISHED",old,old,"fixture-token",old);event("PUBLISHED",old,old,null,old);
        var protectedBefore=jdbc.queryForList("SELECT * FROM invoice_outbox_events WHERE id>? ORDER BY id",second);
        assertThat(retention.compactBatch(cutoff,now,1)).isEqualTo(1);
        assertThat(events.findById(first).orElseThrow().isPayloadCompacted()).isTrue();
        assertThat(events.findById(second).orElseThrow().isPayloadCompacted()).isFalse();
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        assertThat(events.count()).isEqualTo(11);assertThat(compacted()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_outbox_events WHERE id>? ORDER BY id",second)).isEqualTo(protectedBefore);
        assertThat(retention.oldestEligible(cutoff)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_invoice_outbox_payload_retention'",Integer.class)).isEqualTo(1);
    }
    @Test void compactionRetainsIdentityAndMetadataAndTheDatabaseRejectsDuplicateOrRequeuedTombstones() {
        long id=published();
        var before=jdbc.queryForMap("SELECT * FROM invoice_outbox_events WHERE id=?",id);
        long bytes=jdbc.queryForObject("SELECT octet_length(payload) FROM invoice_outbox_events WHERE id=?",Long.class,id);
        assertThat(bytes).isGreaterThan(5000);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        var after=jdbc.queryForMap("SELECT * FROM invoice_outbox_events WHERE id=?",id);
        assertThat(after.get("payload")).isNull();assertThat(after.get("payload_compacted_at")).isNotNull();
        for(String field:List.of("payload","payload_compacted_at")){before.remove(field);after.remove(field);}
        assertThat(after).isEqualTo(before);
        var e=events.findById(id).orElseThrow();assertThat(e.getStatus()).isEqualTo(InvoiceOutboxStatus.PUBLISHED);
        assertThatThrownBy(e::fields).isInstanceOf(IllegalStateException.class).hasMessageContaining("compacted");
        assertThatThrownBy(()->events.saveAndFlush(new InvoiceOutboxEvent(e.getEventId(),Map.of("different","fixture"))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE invoice_outbox_events SET status='PENDING' WHERE id=?",id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE invoice_outbox_events SET payload='{}' WHERE id=?",id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.count()).isEqualTo(1);assertThat(states.claim(id)).isNull();
    }
    @Test void realPublicationAndLateCompletionCannotRepublishOrRestoreACompactedPayload() {
        long id=event("PENDING",null,old,null,null);
        var ticket=states.claim(id);assertThat(ticket.event().fields()).containsEntry("eventId",ticket.event().getEventId());
        assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        states.complete(ticket,true);
        assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        jdbc.update("UPDATE invoice_outbox_events SET published_at=? WHERE id=?",time(old),id);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        states.complete(ticket,false);states.complete(ticket,true);
        assertThat(states.claim(id)).isNull();assertThat(states.due()).doesNotContain(id);
        var e=events.findById(id).orElseThrow();assertThat(e.isPayloadCompacted()).isTrue();assertThat(e.getStatus()).isEqualTo(InvoiceOutboxStatus.PUBLISHED);
        long retry=event("FAILED",null,old,null,null);var retryTicket=states.claim(retry);
        assertThat(retryTicket.event().fields()).containsEntry("eventType","INVOICE_ISSUED");
        assertThat(retention.compactBatch(cutoff,now,100)).isZero();
    }
    @Test void observeReportsCandidatesWithoutChangingPayloadsOrIdentities() {
        published();var before=jdbc.queryForList("SELECT * FROM invoice_outbox_events");
        var registry=new SimpleMeterRegistry();worker(OBSERVE,4,registry).cleanup();
        assertThat(jdbc.queryForList("SELECT * FROM invoice_outbox_events")).isEqualTo(before);
        assertThat(gauge(registry,"compaction.enabled")).isZero();assertThat(gauge(registry,"pending")).isEqualTo(1);
        assertThat(registry.get("billing.outbox.retention.compacted").counter().count()).isZero();
    }
    @Test void lockedRowsStayVisibleAsBacklogWhileOtherPayloadsAreCompacted() throws Exception {
        long id=published();published();published();
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM invoice_outbox_events WHERE id=? FOR UPDATE")) {
                statement.setLong(1,id);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->retention.compactBatch(cutoff,now,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);
                    pool.submit(worker::cleanup).get(5,TimeUnit.SECONDS);
                }
                assertThat(compacted()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(1);assertThat(gauge(registry,"last.compacted")).isZero();
            }
            locked.rollback();
        }
        worker.cleanup();assertThat(compacted()).isEqualTo(3);assertThat(events.count()).isEqualTo(3);
        assertThat(gauge(registry,"pending")).isZero();
    }
    @Test void parallelBatchesRemainBoundedAndEventuallyDrainThePayloadBacklog() throws Exception {
        for(int i=0;i<10;i++)published();event("PUBLISHED",now,old,null,null);
        int count;var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return retention.compactBatch(cutoff,now,2);});
            var b=pool.submit(()->{start.await();return retention.compactBatch(cutoff,now,2);});start.countDown();
            count=a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS);
        }
        assertThat(count).isBetween(2,4);assertThat(compacted()).isEqualTo(count);
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);worker.cleanup();
        assertThat(gauge(registry,"last.compacted")).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(1);
        for(int i=0;i<5;i++)worker.cleanup();
        assertThat(compacted()).isEqualTo(10);assertThat(events.count()).isEqualTo(11);assertThat(gauge(registry,"pending")).isZero();
        assertThat(registry.get("billing.outbox.retention.compacted").counter().count()).isEqualTo(10-count);
    }
    @Test void laterBatchRollbackPreservesPayloadsAndEarlierCommittedCompactionCountersThenRecovers() {
        published();published();long blocked=published();published();
        jdbc.update("UPDATE invoice_outbox_events SET event_id='fixture-block' WHERE id=?",blocked);
        jdbc.execute("CREATE FUNCTION invoice_retention_fixture_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF OLD.event_id='fixture-block' THEN RAISE EXCEPTION 'fixture update rejected'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER invoice_retention_fixture_reject BEFORE UPDATE ON invoice_outbox_events FOR EACH ROW EXECUTE FUNCTION invoice_retention_fixture_reject()");
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,4,registry);worker.cleanup();
        assertThat(compacted()).isEqualTo(2);assertThat(events.findById(blocked).orElseThrow().fields()).isNotEmpty();
        assertThat(registry.get("billing.outbox.retention.compacted").counter().count()).isEqualTo(2);
        assertThat(gauge(registry,"pending")).isEqualTo(-1);
        removeRejection();worker.cleanup();assertThat(compacted()).isEqualTo(4);assertThat(events.count()).isEqualTo(4);
        assertThat(gauge(registry,"pending")).isZero();assertThat(registry.get("billing.outbox.retention.compacted").counter().count()).isEqualTo(4);
    }
}
