package org.demo.notificationservice.notification.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.notificationservice.messaging.*;
import org.demo.notificationservice.messaging.retention.RejectedInboxRetentionRepository;
import org.demo.notificationservice.notification.*;
import org.demo.notificationservice.web.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.notificationservice.notification.retention.SentNotificationRetentionProperties.Mode.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({org.demo.platformsecurity.privacy.PrivacySql.class, org.demo.notificationservice.privacy.NotificationPrivacyGuard.class,SentNotificationRetentionRepository.class,RejectedInboxRetentionRepository.class,NotificationStateStore.class,
        NotificationRetryPolicy.class,NotificationDeadLetterService.class,NotificationEventInbox.class,NotificationService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class SentNotificationRetentionPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("sent_notification_fixture").withUsername("fixture").withPassword("sent-notification-fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired SentNotificationRetentionRepository retention;
    @Autowired RejectedInboxRetentionRepository rejectedRetention;
    @Autowired NotificationRepository notifications;
    @Autowired EventReceiptRepository receipts;
    @Autowired NotificationStateStore states;
    @Autowired NotificationEventInbox inbox;
    @Autowired NotificationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @MockitoBean NotificationDeliveryService delivery;
    private Instant now,cutoff,old;
    private long sequence;
    private SentNotificationRetentionWorker activeWorker;
    @BeforeEach void setup() {
        removeRejection();jdbc.execute("TRUNCATE event_inbox,notification_dead_letters,notifications RESTART IDENTITY");
        now=Instant.now().truncatedTo(ChronoUnit.MICROS);cutoff=now.minus(Duration.ofDays(30));old=cutoff.minusSeconds(60);sequence=0;
    }
    private Timestamp time(Instant value) {return value==null?null:Timestamp.from(value);}
    private CreateNotificationRequest request(long source,String subject,String body) {
        return new CreateNotificationRequest(NotificationEventType.PAYMENT_SUCCEEDED,source,"fixture@example.test",NotificationChannel.EMAIL,subject,body,false);
    }
    private long event(String status,Instant sent,Instant created,Instant updated,String token,Instant lease,Instant next,String failure) {
        var n=notifications.saveAndFlush(new Notification(NotificationEventType.PAYMENT_SUCCEEDED,++sequence,"fixture@example.test",
                NotificationChannel.EMAIL,"Fixture subject","fixture".repeat(800),"fixture-correlation",42L));
        jdbc.update("UPDATE notifications SET status=?,sent_at=?,created_at=?,updated_at=?,processing_token=?,processing_until=?,"
                        + "next_attempt_at=?,failure_reason=?,provider_reference='FIXTURE-SENT',attempt_count=2 WHERE id=?",
                status,time(sent),time(created),time(updated),token,time(lease),time(next),failure,n.getId());return n.getId();
    }
    private long sent() {return event("SENT",old,old,old,null,null,null,null);}
    private void age(long id) {jdbc.update("UPDATE notifications SET created_at=?,updated_at=?,sent_at=? WHERE id=?",time(old),time(old),time(old),id);}
    private long compacted() {return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE content_compacted_at IS NOT NULL",Long.class);}
    private SentNotificationRetentionWorker worker(SentNotificationRetentionProperties.Mode mode,int maxBatches,SimpleMeterRegistry registry) {
        activeWorker=new SentNotificationRetentionWorker(retention,SentNotificationRetentionWorkerTest.properties(mode,maxBatches),registry,Clock.fixed(now,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(SimpleMeterRegistry r,String name) {return r.get("notification.sent.retention."+name).gauge().value();}
    private void removeRejection() {
        jdbc.execute("DROP TRIGGER IF EXISTS sent_notification_fixture_reject ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS sent_notification_fixture_reject()");
    }

    @Test void onlyOldConfirmedUnleasedSentContentQualifiesAndAllThreeTimeBoundariesAreStrict() {
        long first=sent(),second=sent();
        event("PENDING",old,old,old,null,null,null,null);event("FAILED",old,old,old,null,null,null,null);
        event("SENT",null,old,old,null,null,null,null);event("SENT",cutoff,old,old,null,null,null,null);
        event("SENT",old,cutoff,old,null,null,null,null);event("SENT",old,old,cutoff,null,null,null,null);
        event("SENT",old,old,now.plusSeconds(60),null,null,null,null);
        event("SENT",old,old,old,"fixture-token",null,null,null);event("SENT",old,old,old,null,old,null,null);
        event("SENT",old,old,old,null,null,old,null);event("SENT",old,old,old,null,null,null,"fixture failure");
        event("SENT",now,old,old,null,null,null,null);
        var before=jdbc.queryForList("SELECT * FROM notifications WHERE id>? ORDER BY id",second);
        assertThat(retention.compactBatch(cutoff,now,1)).isEqualTo(1);assertThat(notifications.findById(first).orElseThrow().isContentCompacted()).isTrue();
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        assertThat(notifications.count()).isEqualTo(14);assertThat(compacted()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM notifications WHERE id>? ORDER BY id",second)).isEqualTo(before);
        assertThat(retention.oldestEligible(cutoff)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_sent_notification_content_retention'",Integer.class)).isEqualTo(1);
    }
    @Test void compactionClearsOnlySubjectAndBodyWhileKeepingIdentityMetadataAndDatabaseGuards() {
        long id=sent();var before=jdbc.queryForMap("SELECT * FROM notifications WHERE id=?",id);
        assertThat(jdbc.queryForObject("SELECT octet_length(message) FROM notifications WHERE id=?",Long.class,id)).isGreaterThan(5000);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        var after=jdbc.queryForMap("SELECT * FROM notifications WHERE id=?",id);
        assertThat(after.get("subject")).isNull();assertThat(after.get("message")).isNull();assertThat(after.get("content_compacted_at")).isNotNull();
        for(String field:List.of("subject","message","content_compacted_at")){before.remove(field);after.remove(field);}
        assertThat(after).isEqualTo(before);
        assertThatThrownBy(()->notifications.saveAndFlush(new Notification(NotificationEventType.PAYMENT_SUCCEEDED,1L,"fixture@example.test",NotificationChannel.EMAIL,"duplicate","duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
        for(String update:List.of("status='PENDING'","subject='restored'","message='restored'","processing_token='lease'",
                "next_attempt_at=now()","failure_reason='restored'","sent_at=NULL","content_compacted_at=NULL"))
            assertThatThrownBy(()->jdbc.update("UPDATE notifications SET "+update+" WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(notifications.count()).isEqualTo(1);
    }
    @Test void realSentDuplicatesRetriesLateCompletionAndInboxNeverRestoreOrResendCompactedContent() {
        var ticket=states.create(request(99,"Original subject","Original body"));states.complete(ticket,NotificationDeliveryResult.sent("FIXTURE-SENT"),false);
        long id=ticket.notification().getId();age(id);assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        var before=jdbc.queryForMap("SELECT * FROM notifications WHERE id=?",id);
        assertThat(states.create(request(99,"Changed subject","Changed body")).token()).isNull();assertThat(states.retry(id).token()).isNull();
        states.complete(ticket,NotificationDeliveryResult.failed("late failure"),false);states.complete(ticket,NotificationDeliveryResult.sent("late sent"),false);
        var fields=Map.of("eventType","PAYMENT_SUCCEEDED","orderId","99","amount","12.30","recipient","fixture@example.test");
        inbox.accept("accepted-sent-fixture",fields,request(99,"Inbox subject","Inbox body"));inbox.accept("accepted-sent-fixture",fields,request(99,"Inbox subject","Inbox body"));
        assertThatThrownBy(()->inbox.accept("accepted-sent-fixture",Map.of("changed","fixture"),request(99,"ignored","ignored"))).isInstanceOf(IllegalArgumentException.class);
        var dto=service.create(request(99,"Changed subject","Changed body"));assertThat(dto.contentCompacted()).isTrue();
        assertThat(dto.message()).isEqualTo("Content removed by retention policy.");assertThat(service.get(id).contentCompacted()).isTrue();
        assertThat(service.getAll()).singleElement().extracting(NotificationResponse::contentCompacted).isEqualTo(true);
        service.retry(id);verifyNoInteractions(delivery);
        assertThat(jdbc.queryForMap("SELECT * FROM notifications WHERE id=?",id)).isEqualTo(before);
        assertThat(receipts.count()).isEqualTo(1);assertThat(notifications.due(now,org.springframework.data.domain.PageRequest.of(0,20))).doesNotContain(id);
    }
    @Test void failedReplayKeepsFullContentAndResolvedDlqKeepsItsSeparateCopy() {
        var first=states.create(request(98,"Replay subject","Replay body"));states.complete(first,NotificationDeliveryResult.uncertain("fixture unknown outcome"),false);
        long id=first.notification().getId();jdbc.update("UPDATE notifications SET created_at=?,updated_at=? WHERE id=?",time(old),time(old),id);
        long dlq=jdbc.queryForObject("SELECT id FROM notification_dead_letters WHERE notification_id=?",Long.class,id);
        assertThat(retention.compactBatch(cutoff,now,100)).isZero();var replay=states.replay(dlq);
        assertThat(replay.notification().getMessage()).isEqualTo("Replay body");assertThat(replay.token()).isNotNull();
        states.complete(replay,NotificationDeliveryResult.sent("FIXTURE-REPLAY"),false);age(id);
        var deadBefore=jdbc.queryForList("SELECT * FROM notification_dead_letters");assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM notification_dead_letters")).isEqualTo(deadBefore);
        assertThat(states.replay(dlq).token()).isNull();assertThat(service.replayDeadLetter(dlq).contentCompacted()).isTrue();verifyNoInteractions(delivery);
    }
    @Test void observeReportsBacklogWithoutChangingContentOrMetadata() {
        sent();var before=jdbc.queryForList("SELECT * FROM notifications");var registry=new SimpleMeterRegistry();worker(OBSERVE,4,registry).cleanup();
        assertThat(jdbc.queryForList("SELECT * FROM notifications")).isEqualTo(before);assertThat(gauge(registry,"pending")).isEqualTo(1);
        assertThat(gauge(registry,"compaction.enabled")).isZero();assertThat(registry.get("notification.sent.retention.compacted").counter().count()).isZero();
    }
    @Test void lockedContentStaysVisibleAsBacklogWhileOtherRowsCompact() throws Exception {
        long id=sent();sent();sent();var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM notifications WHERE id=? FOR UPDATE")) {
                statement.setLong(1,id);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->retention.compactBatch(cutoff,now,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);
                    pool.submit(worker::cleanup).get(5,TimeUnit.SECONDS);
                }
                assertThat(compacted()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(1);assertThat(gauge(registry,"last.compacted")).isZero();
            }
            locked.rollback();
        }
        worker.cleanup();assertThat(compacted()).isEqualTo(3);assertThat(gauge(registry,"pending")).isZero();
    }
    @Test void parallelBatchesHonorTheirBudgetsAndEventuallyDrainOnlyOldContent() throws Exception {
        for(int i=0;i<10;i++)sent();event("SENT",now,old,old,null,null,null,null);
        int count;var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return retention.compactBatch(cutoff,now,2);});
            var b=pool.submit(()->{start.await();return retention.compactBatch(cutoff,now,2);});start.countDown();count=a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS);
        }
        assertThat(count).isBetween(2,4);assertThat(compacted()).isEqualTo(count);
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);worker.cleanup();assertThat(gauge(registry,"last.compacted")).isEqualTo(2);
        assertThat(gauge(registry,"pending")).isEqualTo(1);for(int i=0;i<5;i++)worker.cleanup();
        assertThat(compacted()).isEqualTo(10);assertThat(notifications.count()).isEqualTo(11);assertThat(gauge(registry,"pending")).isZero();
        assertThat(registry.get("notification.sent.retention.compacted").counter().count()).isEqualTo(10-count);
    }
    @Test void laterBatchRollbackPreservesEarlierCommitCountersAndRecovers() {
        sent();sent();long blocked=sent();sent();
        jdbc.execute("CREATE FUNCTION sent_notification_fixture_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF OLD.id="+blocked+" THEN RAISE EXCEPTION 'fixture update rejected'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER sent_notification_fixture_reject BEFORE UPDATE ON notifications FOR EACH ROW EXECUTE FUNCTION sent_notification_fixture_reject()");
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,4,registry);worker.cleanup();
        assertThat(compacted()).isEqualTo(2);assertThat(notifications.findById(blocked).orElseThrow().getMessage()).isNotNull();
        assertThat(registry.get("notification.sent.retention.compacted").counter().count()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(-1);
        removeRejection();worker.cleanup();assertThat(compacted()).isEqualTo(4);assertThat(gauge(registry,"pending")).isZero();
        assertThat(registry.get("notification.sent.retention.compacted").counter().count()).isEqualTo(4);
    }
    @Test void sentAndRejectedCleanersDoNotModifyEachOthersData() {
        long id=sent();receipts.saveAndFlush(new EventReceipt("rejected-policy-fixture",EventIdentity.hash("fixture"),"REJECTED",null,"fixture error","{\"fixture\":\"payload\"}"));
        jdbc.update("UPDATE event_inbox SET received_at=? WHERE event_id='rejected-policy-fixture'",time(old));
        var sentBefore=jdbc.queryForMap("SELECT * FROM notifications WHERE id=?",id);
        assertThat(rejectedRetention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(jdbc.queryForMap("SELECT * FROM notifications WHERE id=?",id)).isEqualTo(sentBefore);
        var receiptBefore=jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id='rejected-policy-fixture'");
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(jdbc.queryForMap("SELECT * FROM event_inbox WHERE event_id='rejected-policy-fixture'")).isEqualTo(receiptBefore);
    }
}
