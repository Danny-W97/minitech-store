package org.demo.notificationservice.messaging.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.notificationservice.messaging.*;
import org.demo.notificationservice.notification.*;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.platformevents.EventIdentity;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.demo.notificationservice.messaging.retention.RejectedInboxRetentionProperties.Mode.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({org.demo.platformsecurity.privacy.PrivacySql.class, org.demo.notificationservice.privacy.NotificationPrivacyGuard.class,RejectedInboxRetentionRepository.class,RejectedEventStore.class,NotificationEventInbox.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class RejectedInboxRetentionPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rejected_inbox_fixture").withUsername("fixture").withPassword("rejected-inbox-fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired RejectedInboxRetentionRepository retention;
    @Autowired EventReceiptRepository receipts;
    @Autowired RejectedEventStore rejected;
    @Autowired NotificationEventInbox inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    private Instant now,cutoff,old;
    private int sequence;
    // Gauges use weak references; keep their owner alive during assertions.
    private RejectedInboxRetentionWorker activeWorker;
    @BeforeEach void setup() {
        removeRejection();jdbc.execute("TRUNCATE event_inbox,notification_dead_letters,notifications RESTART IDENTITY");
        now=Instant.now().truncatedTo(ChronoUnit.MICROS);cutoff=now.minus(Duration.ofDays(30));old=cutoff.minusSeconds(60);sequence=0;
    }
    private Timestamp time(Instant value) {return Timestamp.from(value);}
    private String event(String status,Instant received,Long related,String payload,String error) {
        String id="fixture-%03d".formatted(++sequence);
        // Real rejection writers serialize a field map; fixtures must also contain valid JSON.
        String document=payload==null?null:tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("fixture",payload));
        receipts.saveAndFlush(new EventReceipt(id,EventIdentity.hash(id),status,related,error,document));
        jdbc.update("UPDATE event_inbox SET received_at=? WHERE event_id=?",time(received),id);return id;
    }
    private String rejected() {return event("REJECTED",old,null,"{\"fixture\":\""+"address".repeat(800)+"\"}","fixture-error@example.test");}
    private long compacted() {return jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE diagnostics_compacted_at IS NOT NULL",Long.class);}
    private RejectedInboxRetentionWorker worker(RejectedInboxRetentionProperties.Mode mode,int maxBatches,SimpleMeterRegistry registry) {
        activeWorker=new RejectedInboxRetentionWorker(retention,RejectedInboxRetentionWorkerTest.properties(mode,maxBatches),registry,Clock.fixed(now,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(SimpleMeterRegistry r,String name) {return r.get("notification.rejected.inbox.retention."+name).gauge().value();}
    private void removeRejection() {
        jdbc.execute("DROP TRIGGER IF EXISTS rejected_inbox_fixture_reject ON event_inbox");
        jdbc.execute("DROP FUNCTION IF EXISTS rejected_inbox_fixture_reject()");
    }
    private CreateNotificationRequest request() {
        return new CreateNotificationRequest(NotificationEventType.PAYMENT_SUCCEEDED,12L,"fixture@example.test",
                NotificationChannel.EMAIL,"fixture subject","fixture queued body",false);
    }

    @Test void onlyOldUnlinkedRejectedDiagnosticsQualifyWithAStrictBoundary() {
        String first=rejected();rejected();
        event("REJECTED",old,null,null,"error-only");String last=event("REJECTED",old,null,"payload-only",null);
        event("ACCEPTED",old,null,"protected-accepted","protected-error");event("PENDING",old,null,"protected-pending",null);
        event("REJECTED",cutoff,null,"boundary",null);event("REJECTED",now.plusSeconds(60),null,"future",null);
        event("REJECTED",old,42L,"linked",null);event("REJECTED",old,null,null,null);
        var protectedBefore=jdbc.queryForList("SELECT * FROM event_inbox WHERE event_id>? ORDER BY event_id",last);
        assertThat(retention.compactBatch(cutoff,now,1)).isEqualTo(1);
        assertThat(receipts.findById(first).orElseThrow().isDiagnosticsCompacted()).isTrue();
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(3);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        assertThat(receipts.count()).isEqualTo(10);assertThat(compacted()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT * FROM event_inbox WHERE event_id>? ORDER BY event_id",last)).isEqualTo(protectedBefore);
        assertThat(retention.oldestEligible(cutoff)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_rejected_inbox_diagnostics_retention'",Integer.class)).isEqualTo(1);
    }
    @Test void compactionClearsPayloadAndErrorButPreservesIdentityHashStatusAndTime() {
        String id=rejected();var before=jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id=?",id);
        assertThat(jdbc.queryForObject("SELECT octet_length(payload) FROM event_inbox WHERE event_id=?",Long.class,id)).isGreaterThan(5000);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        var after=jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id=?",id);
        assertThat(after.get("payload")).isNull();assertThat(after.get("error")).isNull();assertThat(after.get("diagnostics_compacted_at")).isNotNull();
        for(String field:List.of("payload","error","diagnostics_compacted_at")){before.remove(field);after.remove(field);}
        assertThat(after).isEqualTo(before);
        assertThatThrownBy(()->receipts.saveAndFlush(new EventReceipt(id,EventIdentity.hash("different"),"REJECTED",null,null,null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        for(String update:List.of("status='ACCEPTED'","related_id=42","payload='{\"fixture\":\"restored\"}'","error='restored'"))
            assertThatThrownBy(()->jdbc.update("UPDATE event_inbox SET "+update+" WHERE event_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(receipts.count()).isEqualTo(1);
    }
    @Test void realRejectedRedeliveryCannotRestoreDiagnosticsOrBypassHashConflictProtection() {
        var fields=Map.of("recipient","fixture@example.test","badField","diagnostic-original");
        rejected.reject("fixture-stream","1-0",fields,"fixture malformed reason");
        String id="rejected-"+EventIdentity.hash("fixture-stream:1-0");
        jdbc.update("UPDATE event_inbox SET received_at=? WHERE event_id=?",time(old),id);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        var before=jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id=?",id);
        rejected.reject("fixture-stream","1-0",fields,"later diagnostic");
        // Existing stream/record receipt wins even if a redelivery carries changed fields.
        rejected.reject("fixture-stream","1-0",Map.of("changed","fixture"),"changed diagnostic");
        inbox.accept(id,fields,request());
        assertThatThrownBy(()->inbox.accept(id,Map.of("changed","fixture"),request()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different content");
        assertThat(jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id=?",id)).isEqualTo(before);
        assertThat(receipts.findById(id).orElseThrow().getPayloadHash()).isEqualTo(EventIdentity.fingerprint(fields));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications",Integer.class)).isZero();
    }
    @Test void acceptedReceiptAndItsQueuedNotificationRemainIntactAndDeduplicated() {
        var fields=Map.of("eventType","PAYMENT_SUCCEEDED","orderId","12","amount","12.30","recipient","fixture@example.test");
        inbox.accept("accepted-fixture",fields,request());
        jdbc.update("UPDATE event_inbox SET received_at=? WHERE event_id='accepted-fixture'",time(old));rejected();
        var before=jdbc.queryForList("SELECT * FROM notifications");
        var receipt=jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id='accepted-fixture'");
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        inbox.accept("accepted-fixture",fields,request());
        assertThatThrownBy(()->inbox.accept("accepted-fixture",Map.of("changed","fixture"),request())).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForList("SELECT * FROM notifications")).isEqualTo(before).hasSize(1);
        assertThat(jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id='accepted-fixture'")).isEqualTo(receipt);
    }
    @Test void observeShowsBacklogWithoutUpdatingDiagnosticsOrFences() {
        rejected();var before=jdbc.queryForList("SELECT * FROM event_inbox");
        var registry=new SimpleMeterRegistry();worker(OBSERVE,4,registry).cleanup();
        assertThat(jdbc.queryForList("SELECT * FROM event_inbox")).isEqualTo(before);
        assertThat(gauge(registry,"compaction.enabled")).isZero();assertThat(gauge(registry,"pending")).isEqualTo(1);
        assertThat(registry.get("notification.rejected.inbox.retention.compacted").counter().count()).isZero();
    }
    @Test void lockedRowsRemainBacklogWhileOtherDiagnosticsAreCompacted() throws Exception {
        String id=rejected();rejected();rejected();
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT event_id FROM event_inbox WHERE event_id=? FOR UPDATE")) {
                statement.setString(1,id);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->retention.compactBatch(cutoff,now,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);
                    pool.submit(worker::cleanup).get(5,TimeUnit.SECONDS);
                }
                assertThat(compacted()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(1);assertThat(gauge(registry,"last.compacted")).isZero();
            }
            locked.rollback();
        }
        worker.cleanup();assertThat(compacted()).isEqualTo(3);assertThat(receipts.count()).isEqualTo(3);assertThat(gauge(registry,"pending")).isZero();
    }
    @Test void parallelBatchesStayBoundedAndEventuallyDrainDiagnosticsOnly() throws Exception {
        for(int i=0;i<10;i++)rejected();event("REJECTED",now,null,"recent","recent-error");
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
        assertThat(compacted()).isEqualTo(10);assertThat(receipts.count()).isEqualTo(11);assertThat(gauge(registry,"pending")).isZero();
        assertThat(registry.get("notification.rejected.inbox.retention.compacted").counter().count()).isEqualTo(10-count);
    }
    @Test void laterBatchRollbackPreservesEarlierCommitCountersAndRecovers() {
        rejected();rejected();String blocked=rejected();rejected();
        jdbc.execute("CREATE FUNCTION rejected_inbox_fixture_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF OLD.event_id='"+blocked+"' THEN RAISE EXCEPTION 'fixture update rejected'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER rejected_inbox_fixture_reject BEFORE UPDATE ON event_inbox FOR EACH ROW EXECUTE FUNCTION rejected_inbox_fixture_reject()");
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,4,registry);worker.cleanup();
        assertThat(compacted()).isEqualTo(2);assertThat(receipts.findById(blocked).orElseThrow().isDiagnosticsCompacted()).isFalse();
        assertThat(registry.get("notification.rejected.inbox.retention.compacted").counter().count()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(-1);
        removeRejection();worker.cleanup();assertThat(compacted()).isEqualTo(4);assertThat(receipts.count()).isEqualTo(4);
        assertThat(gauge(registry,"pending")).isZero();assertThat(registry.get("notification.rejected.inbox.retention.compacted").counter().count()).isEqualTo(4);
    }
}
