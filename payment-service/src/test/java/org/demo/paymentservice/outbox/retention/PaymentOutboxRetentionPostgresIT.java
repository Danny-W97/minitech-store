package org.demo.paymentservice.outbox.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.paymentservice.outbox.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.math.BigDecimal;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.paymentservice.outbox.retention.PaymentOutboxRetentionProperties.Mode.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentOutboxRetentionRepository.class,PaymentOutboxStateStore.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class PaymentOutboxRetentionPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_retention_fixture").withUsername("fixture").withPassword("payment-retention-fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired PaymentOutboxRetentionRepository retention;
    @Autowired PaymentOutboxRepository events;
    @Autowired PaymentOutboxStateStore states;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    private Instant now,cutoff,old;
    private int sequence;
    // Keep Micrometer's weak-reference gauge targets alive for the duration of assertions.
    private PaymentOutboxRetentionWorker activeWorker;
    @BeforeEach void setup() {
        removeRejection();jdbc.execute("TRUNCATE payment_outbox_events RESTART IDENTITY");
        now=Instant.now().truncatedTo(ChronoUnit.MICROS);cutoff=now.minus(Duration.ofDays(30));old=cutoff.minusSeconds(60);sequence=0;
    }
    private Timestamp time(Instant value) {return value==null?null:Timestamp.from(value);}
    private long event(String status,Instant published,Instant created,String token,Instant lease) {
        int number=++sequence;
        var e=events.saveAndFlush(new PaymentOutboxEvent("PAYMENT_SUCCEEDED",(long)number,new BigDecimal("12.34"),
                "fixture-"+number+"@example.test","address".repeat(100),"fixture-correlation",42L));
        jdbc.update("UPDATE payment_outbox_events SET status=?,published_at=?,created_at=?,processing_token=?,processing_until=?,"
                + "attempts=2,next_attempt_at=?,last_error='fixture diagnostic' WHERE id=?",
                status,time(published),time(created),token,time(lease),time(now.minusSeconds(1)),e.getId());
        return e.getId();
    }
    private long published() {return event("PUBLISHED",old,old,null,null);}
    private long compacted() {return jdbc.queryForObject("SELECT count(*) FROM payment_outbox_events WHERE payload_compacted_at IS NOT NULL",Long.class);}
    private PaymentOutboxRetentionWorker worker(PaymentOutboxRetentionProperties.Mode mode,int maxBatches,SimpleMeterRegistry registry) {
        activeWorker=new PaymentOutboxRetentionWorker(retention,PaymentOutboxRetentionWorkerTest.properties(mode,maxBatches),registry,Clock.fixed(now,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(SimpleMeterRegistry r,String name) {return r.get("payment.outbox.retention."+name).gauge().value();}
    private void removeRejection() {
        jdbc.execute("DROP TRIGGER IF EXISTS payment_retention_fixture_reject ON payment_outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS payment_retention_fixture_reject()");
    }

    @Test void onlyOldPublishedUnleasedContactsQualifyAndTheBoundaryIsStrict() {
        long first=published(),second=published();
        event("PENDING",old,old,null,null);event("FAILED",old,old,null,null);
        event("PUBLISHED",null,old,null,null);event("PUBLISHED",cutoff,old,null,null);
        event("PUBLISHED",old,cutoff,null,null);event("PUBLISHED",now,old,null,null);
        event("PUBLISHED",old,now.plusSeconds(60),null,null);
        event("PUBLISHED",old,old,"fixture-token",old);event("PUBLISHED",old,old,null,old);
        var protectedBefore=jdbc.queryForList("SELECT * FROM payment_outbox_events WHERE id>? ORDER BY id",second);
        assertThat(retention.compactBatch(cutoff,now,1)).isEqualTo(1);
        assertThat(events.findById(first).orElseThrow().isPayloadCompacted()).isTrue();
        assertThat(events.findById(second).orElseThrow().isPayloadCompacted()).isFalse();
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        assertThat(events.count()).isEqualTo(11);assertThat(compacted()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM payment_outbox_events WHERE id>? ORDER BY id",second)).isEqualTo(protectedBefore);
        assertThat(retention.oldestEligible(cutoff)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_payment_outbox_payload_retention'",Integer.class)).isEqualTo(1);
    }
    @Test void compactionClearsOnlyContactCopiesAndKeepsIdentityDeduplicationAndPublicationGuards() {
        long id=published();
        var before=jdbc.queryForMap("SELECT * FROM payment_outbox_events WHERE id=?",id);
        var original=events.findById(id).orElseThrow();String eventId=original.getEventId();
        long bytes=jdbc.queryForObject("SELECT octet_length(delivery_address) FROM payment_outbox_events WHERE id=?",Long.class,id);
        assertThat(bytes).isGreaterThan(500);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        var after=jdbc.queryForMap("SELECT * FROM payment_outbox_events WHERE id=?",id);
        assertThat(after.get("recipient")).isNull();assertThat(after.get("delivery_address")).isNull();assertThat(after.get("payload_compacted_at")).isNotNull();
        for(String field:List.of("recipient","delivery_address","payload_compacted_at")){before.remove(field);after.remove(field);}
        assertThat(after).isEqualTo(before);
        var e=events.findById(id).orElseThrow();assertThat(e.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(e.getEventId()).isEqualTo(eventId);
        // event_key intentionally remains unchanged, including its recipient component: this is not anonymization.
        assertThat(e.getEventKey()).isEqualTo(original.getEventKey()).contains(original.getRecipient());
        assertThat(events.findByEventKey(original.getEventKey())).isPresent();
        var redis=mock(StringRedisTemplate.class);var publisher=new PaymentEventPublisher(redis,"fixture-unused");
        assertThatThrownBy(()->publisher.publish(e)).isInstanceOf(IllegalStateException.class).hasMessageContaining("compacted");
        verifyNoInteractions(redis);
        assertThatThrownBy(()->events.saveAndFlush(new PaymentOutboxEvent(e.getEventType(),e.getOrderId(),e.getAmount(),original.getRecipient())))
                .isInstanceOf(DataIntegrityViolationException.class);
        for(String update:List.of("status='PENDING'","recipient='restored@example.test'","delivery_address='restored'",
                "processing_token='new-lease'","published_at=NULL","payload_compacted_at=NULL"))
            assertThatThrownBy(()->jdbc.update("UPDATE payment_outbox_events SET "+update+" WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.count()).isEqualTo(1);assertThat(states.claim(id)).isNull();
    }
    @Test void realPublicationAndLateCompletionCannotRepublishOrRestoreCompactedContacts() {
        long id=event("PENDING",null,old,null,null);
        var ticket=states.claim(id);assertThat(ticket.event().getRecipient()).isNotNull();
        assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        states.complete(ticket,true);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        jdbc.update("UPDATE payment_outbox_events SET published_at=? WHERE id=?",time(old),id);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        states.complete(ticket,false);states.complete(ticket,true);
        assertThat(states.claim(id)).isNull();assertThat(states.due()).doesNotContain(id);
        var e=events.findById(id).orElseThrow();assertThat(e.isPayloadCompacted()).isTrue();assertThat(e.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(e.getRecipient()).isNull();assertThat(e.getDeliveryAddress()).isNull();
        long retry=event("FAILED",null,old,null,null);var retryTicket=states.claim(retry);
        assertThat(retryTicket.event().getRecipient()).isNotNull();assertThat(retryTicket.event().getDeliveryAddress()).isNotNull();
        assertThat(retention.compactBatch(cutoff,now,100)).isZero();
    }
    @Test void observeReportsCandidatesWithoutChangingContactsOrIdentities() {
        published();var before=jdbc.queryForList("SELECT * FROM payment_outbox_events");
        var registry=new SimpleMeterRegistry();worker(OBSERVE,4,registry).cleanup();
        assertThat(jdbc.queryForList("SELECT * FROM payment_outbox_events")).isEqualTo(before);
        assertThat(gauge(registry,"compaction.enabled")).isZero();assertThat(gauge(registry,"pending")).isEqualTo(1);
        assertThat(registry.get("payment.outbox.retention.compacted").counter().count()).isZero();
    }
    @Test void lockedRowsStayVisibleAsBacklogWhileOtherContactsAreCompacted() throws Exception {
        long id=published();published();published();
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM payment_outbox_events WHERE id=? FOR UPDATE")) {
                statement.setLong(1,id);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->retention.compactBatch(cutoff,now,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);
                    pool.submit(worker::cleanup).get(5,TimeUnit.SECONDS);
                }
                assertThat(compacted()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(1);assertThat(gauge(registry,"last.compacted")).isZero();
            }
            locked.rollback();
        }
        worker.cleanup();assertThat(compacted()).isEqualTo(3);assertThat(events.count()).isEqualTo(3);assertThat(gauge(registry,"pending")).isZero();
    }
    @Test void parallelBatchesRemainBoundedAndEventuallyDrainTheContactBacklog() throws Exception {
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
        assertThat(registry.get("payment.outbox.retention.compacted").counter().count()).isEqualTo(10-count);
    }
    @Test void laterBatchRollbackPreservesContactsAndEarlierCommitCountersThenRecovers() {
        published();published();long blocked=published();published();
        jdbc.execute("CREATE FUNCTION payment_retention_fixture_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF OLD.id="+blocked+" THEN RAISE EXCEPTION 'fixture update rejected'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER payment_retention_fixture_reject BEFORE UPDATE ON payment_outbox_events FOR EACH ROW EXECUTE FUNCTION payment_retention_fixture_reject()");
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,4,registry);worker.cleanup();
        assertThat(compacted()).isEqualTo(2);assertThat(events.findById(blocked).orElseThrow().getDeliveryAddress()).isNotNull();
        assertThat(registry.get("payment.outbox.retention.compacted").counter().count()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(-1);
        removeRejection();worker.cleanup();assertThat(compacted()).isEqualTo(4);assertThat(events.count()).isEqualTo(4);
        assertThat(gauge(registry,"pending")).isZero();assertThat(registry.get("payment.outbox.retention.compacted").counter().count()).isEqualTo(4);
    }
}
