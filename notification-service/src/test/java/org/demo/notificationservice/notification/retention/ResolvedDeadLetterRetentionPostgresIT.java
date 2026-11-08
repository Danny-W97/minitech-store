package org.demo.notificationservice.notification.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.notificationservice.notification.*;
import org.demo.notificationservice.web.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.notificationservice.notification.retention.ResolvedDeadLetterRetentionProperties.Mode.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({org.demo.platformsecurity.privacy.PrivacySql.class, org.demo.notificationservice.privacy.NotificationPrivacyGuard.class,ResolvedDeadLetterRetentionRepository.class,SentNotificationRetentionRepository.class,NotificationStateStore.class,
        NotificationRetryPolicy.class,NotificationDeadLetterService.class,NotificationService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Testcontainers
class ResolvedDeadLetterRetentionPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("resolved_dlq_fixture").withUsername("fixture").withPassword("resolved-dlq-fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired ResolvedDeadLetterRetentionRepository retention;
    @Autowired SentNotificationRetentionRepository sentRetention;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationDeadLetterRepository entries;
    @Autowired NotificationDeadLetterService dead;
    @Autowired NotificationStateStore states;
    @Autowired NotificationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean NotificationDeliveryService delivery;
    private Instant now,cutoff,old;
    private long sequence;
    private ResolvedDeadLetterRetentionWorker activeWorker;
    @BeforeEach void setup() {
        removeRejection();jdbc.execute("TRUNCATE event_inbox,notification_dead_letters,notifications RESTART IDENTITY");
        now=Instant.now().truncatedTo(ChronoUnit.MICROS);cutoff=now.minus(Duration.ofDays(30));old=cutoff.minusSeconds(60);sequence=0;
    }
    private Timestamp time(Instant value) {return value==null?null:Timestamp.from(value);}
    private CreateNotificationRequest request(long id) {
        return new CreateNotificationRequest(NotificationEventType.PAYMENT_SUCCEEDED,id,"fixture@example.test",NotificationChannel.EMAIL,"Fixture subject","fixture".repeat(800),false);
    }
    private long parent(long id) {return jdbc.queryForObject("SELECT notification_id FROM notification_dead_letters WHERE id=?",Long.class,id);}
    private void age(long id) {
        jdbc.update("UPDATE notifications SET created_at=?,updated_at=?,sent_at=? WHERE id=?",time(old),time(old),time(old),parent(id));
        jdbc.update("UPDATE notification_dead_letters SET created_at=?,updated_at=?,resolved_at=? WHERE id=?",time(old),time(old),time(old),id);
    }
    private long resolved() {
        var n=new Notification(NotificationEventType.PAYMENT_SUCCEEDED,++sequence,"fixture@example.test",NotificationChannel.EMAIL,"Fixture subject","fixture".repeat(800));
        n.markFailed("fixture failure@example.test",null);n=notifications.saveAndFlush(n);
        var d=new NotificationDeadLetterEntry(n);d.markResolved();d=entries.saveAndFlush(d);
        jdbc.update("UPDATE notifications SET status='SENT',failure_reason=NULL,sent_at=? WHERE id=?",time(old),n.getId());age(d.getId());return d.getId();
    }
    private long compacted() {return jdbc.queryForObject("SELECT count(*) FROM notification_dead_letters WHERE content_compacted_at IS NOT NULL",Long.class);}
    private ResolvedDeadLetterRetentionWorker worker(ResolvedDeadLetterRetentionProperties.Mode mode,int maxBatches,SimpleMeterRegistry registry) {
        activeWorker=new ResolvedDeadLetterRetentionWorker(retention,ResolvedDeadLetterRetentionWorkerTest.properties(mode,maxBatches),registry,Clock.fixed(now,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(SimpleMeterRegistry r,String name) {return r.get("notification.resolved.dlq.retention."+name).gauge().value();}
    private void removeRejection() {
        jdbc.execute("DROP TRIGGER IF EXISTS resolved_dlq_fixture_reject ON notification_dead_letters");
        jdbc.execute("DROP FUNCTION IF EXISTS resolved_dlq_fixture_reject()");
    }

    @Test void onlyOldResolvedCopiesWithOldConfirmedSentParentsQualify() {
        long first=resolved(),second=resolved(),id;
        id=resolved();jdbc.update("UPDATE notification_dead_letters SET status='OPEN',resolved_at=NULL WHERE id=?",id);
        id=resolved();jdbc.update("UPDATE notification_dead_letters SET resolved_at=NULL WHERE id=?",id);
        for(String column:List.of("created_at","updated_at","resolved_at")) {id=resolved();jdbc.update("UPDATE notification_dead_letters SET "+column+"=? WHERE id=?",time(cutoff),id);}
        for(String column:List.of("created_at","updated_at","sent_at")) {id=resolved();jdbc.update("UPDATE notifications SET "+column+"=? WHERE id=?",time(cutoff),parent(id));}
        id=resolved();jdbc.update("UPDATE notification_dead_letters SET updated_at=? WHERE id=?",time(now.plusSeconds(60)),id);
        id=resolved();jdbc.update("UPDATE notifications SET status='FAILED' WHERE id=?",parent(id));
        id=resolved();jdbc.update("UPDATE notifications SET status='PENDING' WHERE id=?",parent(id));
        id=resolved();jdbc.update("UPDATE notifications SET sent_at=NULL WHERE id=?",parent(id));
        id=resolved();jdbc.update("UPDATE notifications SET processing_token='fixture' WHERE id=?",parent(id));
        id=resolved();jdbc.update("UPDATE notifications SET processing_until=? WHERE id=?",time(old),parent(id));
        id=resolved();jdbc.update("UPDATE notifications SET next_attempt_at=? WHERE id=?",time(old),parent(id));
        id=resolved();jdbc.update("UPDATE notifications SET failure_reason='fixture' WHERE id=?",parent(id));
        id=resolved();jdbc.update("DELETE FROM notifications WHERE id=?",parent(id));
        var before=jdbc.queryForList("SELECT * FROM notification_dead_letters WHERE id>? ORDER BY id",second);
        assertThat(retention.compactBatch(cutoff,now,1)).isEqualTo(1);assertThat(entries.findById(first).orElseThrow().isContentCompacted()).isTrue();
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        assertThat(compacted()).isEqualTo(2);assertThat(jdbc.queryForList("SELECT * FROM notification_dead_letters WHERE id>? ORDER BY id",second)).isEqualTo(before);
        assertThat(retention.oldestEligible(cutoff)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_resolved_dead_letter_content_retention'",Integer.class)).isEqualTo(1);
    }
    @Test void compactionClearsThreeFieldsIncrementsVersionAndPreservesAllOtherMetadata() {
        long id=resolved();var before=jdbc.queryForMap("SELECT * FROM notification_dead_letters WHERE id=?",id);
        var parentBefore=jdbc.queryForList("SELECT * FROM notifications");
        assertThat(jdbc.queryForObject("SELECT octet_length(message) FROM notification_dead_letters WHERE id=?",Long.class,id)).isGreaterThan(5000);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);var after=jdbc.queryForMap("SELECT * FROM notification_dead_letters WHERE id=?",id);
        for(String field:List.of("subject","message","failure_reason"))assertThat(after.get(field)).isNull();
        assertThat(after.get("content_compacted_at")).isNotNull();assertThat(((Number)after.get("row_version")).longValue()).isEqualTo(((Number)before.get("row_version")).longValue()+1);
        for(String field:List.of("subject","message","failure_reason","content_compacted_at","row_version")){before.remove(field);after.remove(field);}
        assertThat(after).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM notifications")).isEqualTo(parentBefore);
        for(String update:List.of("status='OPEN'","subject='restored'","message='restored'","failure_reason='restored'","resolved_at=NULL","content_compacted_at=NULL"))
            assertThatThrownBy(()->jdbc.update("UPDATE notification_dead_letters SET "+update+" WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        var duplicateSource=notifications.findById(parent(id)).orElseThrow();duplicateSource.markFailed("duplicate fixture",null);
        var duplicate=new NotificationDeadLetterEntry(duplicateSource);
        assertThatThrownBy(()->entries.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void realReplaySuccessThenCompactionKeepsSentReplayAndLateCompletionIdempotent() {
        var first=states.create(request(99));states.complete(first,NotificationDeliveryResult.uncertain("fixture uncertain"),false);
        long id=jdbc.queryForObject("SELECT id FROM notification_dead_letters",Long.class);
        var replay=states.replay(id);assertThat(replay.token()).isNotNull();assertThat(replay.notification().getMessage()).hasSize(5600);
        states.complete(replay,NotificationDeliveryResult.sent("FIXTURE-SENT"),false);age(id);assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        var before=jdbc.queryForMap("SELECT * FROM notification_dead_letters WHERE id=?",id);
        assertThat(service.replayDeadLetter(id).status()).isEqualTo(NotificationStatus.SENT);assertThat(states.replay(id).token()).isNull();
        states.complete(first,NotificationDeliveryResult.failed("late failure"),false);states.complete(replay,NotificationDeliveryResult.failed("late failure"),false);
        assertThat(jdbc.queryForMap("SELECT * FROM notification_dead_letters WHERE id=?",id)).isEqualTo(before);
        var dto=NotificationDeadLetterResponse.from(dead.get(id));assertThat(dto.contentCompacted()).isTrue();assertThat(dto.message()).isEqualTo("Content removed by retention policy.");
        assertThat(dead.getOpenEntries()).isEmpty();verifyNoInteractions(delivery);
    }
    @Test void staleDetachedMergeAndServiceWritesCannotRestoreContentAndInconsistentReplayIsRejected() {
        long id=resolved();var stale=entries.findById(id).orElseThrow();var staleFailure=notifications.findById(parent(id)).orElseThrow();staleFailure.markFailed("stale failure",null);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);var before=jdbc.queryForMap("SELECT * FROM notification_dead_letters WHERE id=?",id);
        stale.refreshFrom(staleFailure);assertThatThrownBy(()->entries.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        dead.resolve(stale);assertThatThrownBy(()->dead.record(staleFailure)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM notification_dead_letters WHERE id=?",id)).isEqualTo(before);
        jdbc.update("UPDATE notifications SET status='FAILED' WHERE id=?",parent(id));
        assertThatThrownBy(()->states.replay(id)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.queryForMap("SELECT * FROM notification_dead_letters WHERE id=?",id)).isEqualTo(before);verifyNoInteractions(delivery);
    }
    @Test void replayRefreshesAnAlreadyManagedCopyAfterConcurrentCompaction() {
        long id=resolved();var tx=new TransactionTemplate(manager);
        tx.execute(status -> {
            var cached=dead.get(id);assertThat(cached.isContentCompacted()).isFalse();
            try(var pool=Executors.newSingleThreadExecutor()) {assertThat(pool.submit(()->retention.compactBatch(cutoff,now,100)).get(5,TimeUnit.SECONDS)).isEqualTo(1);}
            catch(Exception error){throw new IllegalStateException(error);}
            assertThat(cached.isContentCompacted()).isFalse();assertThat(states.replay(id).token()).isNull();
            assertThat(cached.isContentCompacted()).isTrue();assertThat(cached.getMessage()).isNull();return null;
        });
        assertThat(entries.findById(id).orElseThrow().isContentCompacted()).isTrue();verifyNoInteractions(delivery);
    }
    @Test void openFailedAndInFlightReplayKeepFullContentUntilSuccess() {
        var first=states.create(request(98));states.complete(first,NotificationDeliveryResult.uncertain("fixture uncertain"),false);
        long id=jdbc.queryForObject("SELECT id FROM notification_dead_letters",Long.class);
        jdbc.update("UPDATE notification_dead_letters SET created_at=?,updated_at=? WHERE id=?",time(old),time(old),id);
        assertThat(retention.compactBatch(cutoff,now,100)).isZero();var replay=states.replay(id);
        assertThat(replay.notification().getMessage()).hasSize(5600);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        states.complete(replay,NotificationDeliveryResult.uncertain("fixture retry uncertain"),false);assertThat(dead.get(id).getStatus()).isEqualTo(DeadLetterStatus.OPEN);
        assertThat(dead.get(id).getMessage()).hasSize(5600);assertThat(retention.compactBatch(cutoff,now,100)).isZero();
        replay=states.replay(id);states.complete(replay,NotificationDeliveryResult.sent("FIXTURE-SENT"),false);age(id);
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);verifyNoInteractions(delivery);
    }
    @Test void observeReportsBacklogWithoutChangingCopiesOrVersions() {
        resolved();var before=jdbc.queryForList("SELECT * FROM notification_dead_letters");var registry=new SimpleMeterRegistry();worker(OBSERVE,4,registry).cleanup();
        assertThat(jdbc.queryForList("SELECT * FROM notification_dead_letters")).isEqualTo(before);assertThat(gauge(registry,"pending")).isEqualTo(1);
        assertThat(gauge(registry,"compaction.enabled")).isZero();assertThat(registry.get("notification.resolved.dlq.retention.compacted").counter().count()).isZero();
    }
    @Test void lockedChildIsSkippedAndRemainsVisibleWhileOtherCopiesCompact() throws Exception {
        long id=resolved();resolved();resolved();var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM notification_dead_letters WHERE id=? FOR UPDATE")) {
                statement.setLong(1,id);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {
                    assertThat(pool.submit(()->retention.compactBatch(cutoff,now,2)).get(5,TimeUnit.SECONDS)).isEqualTo(2);pool.submit(worker::cleanup).get(5,TimeUnit.SECONDS);
                }
                assertThat(compacted()).isEqualTo(2);assertThat(gauge(registry,"pending")).isEqualTo(1);assertThat(gauge(registry,"last.compacted")).isZero();
            }locked.rollback();
        }
        worker.cleanup();assertThat(compacted()).isEqualTo(3);assertThat(gauge(registry,"pending")).isZero();
    }
    @Test void parentLockDoesNotCauseCollectorToAcquireLocksInReverseOrder() throws Exception {
        long id=resolved();
        try(var locked=datasource.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM notifications WHERE id=? FOR UPDATE")) {
                statement.setLong(1,parent(id));try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                try(var pool=Executors.newSingleThreadExecutor()) {assertThat(pool.submit(()->retention.compactBatch(cutoff,now,1)).get(5,TimeUnit.SECONDS)).isEqualTo(1);}
            }locked.rollback();
        }
        assertThat(compacted()).isEqualTo(1);
    }
    @Test void parallelBatchesHonorLimitsAndEventuallyDrainOnlyOldResolvedCopies() throws Exception {
        for(int i=0;i<10;i++)resolved();long fresh=resolved();jdbc.update("UPDATE notification_dead_letters SET resolved_at=? WHERE id=?",time(now),fresh);
        int count;var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return retention.compactBatch(cutoff,now,2);});var b=pool.submit(()->{start.await();return retention.compactBatch(cutoff,now,2);});
            start.countDown();count=a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS);
        }
        assertThat(count).isBetween(2,4);assertThat(compacted()).isEqualTo(count);
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,1,registry);worker.cleanup();assertThat(gauge(registry,"last.compacted")).isEqualTo(2);
        for(int i=0;i<5;i++)worker.cleanup();assertThat(compacted()).isEqualTo(10);assertThat(entries.count()).isEqualTo(11);assertThat(gauge(registry,"pending")).isZero();
        assertThat(registry.get("notification.resolved.dlq.retention.compacted").counter().count()).isEqualTo(10-count);
    }
    @Test void laterBatchRollbackPreservesEarlierCommitCountersAndRecovers() {
        resolved();resolved();long blocked=resolved();resolved();
        jdbc.execute("CREATE FUNCTION resolved_dlq_fixture_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.id="+blocked+" THEN RAISE EXCEPTION 'fixture update rejected'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER resolved_dlq_fixture_reject BEFORE UPDATE ON notification_dead_letters FOR EACH ROW EXECUTE FUNCTION resolved_dlq_fixture_reject()");
        var registry=new SimpleMeterRegistry();var worker=worker(COMPACT,4,registry);worker.cleanup();assertThat(compacted()).isEqualTo(2);
        assertThat(entries.findById(blocked).orElseThrow().getMessage()).isNotNull();assertThat(registry.get("notification.resolved.dlq.retention.compacted").counter().count()).isEqualTo(2);
        removeRejection();worker.cleanup();assertThat(compacted()).isEqualTo(4);assertThat(gauge(registry,"pending")).isZero();assertThat(registry.get("notification.resolved.dlq.retention.compacted").counter().count()).isEqualTo(4);
    }
    @Test void sentAndDlqPoliciesWorkIndependentlyInEitherCompactionOrder() {
        long first=resolved();var parentBefore=jdbc.queryForList("SELECT * FROM notifications");assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM notifications")).isEqualTo(parentBefore);assertThat(sentRetention.compactBatch(cutoff,now,100)).isEqualTo(1);
        long second=resolved();assertThat(sentRetention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(entries.findById(second).orElseThrow().isContentCompacted()).isFalse();
        assertThat(retention.compactBatch(cutoff,now,100)).isEqualTo(1);assertThat(entries.findById(first).orElseThrow().isContentCompacted()).isTrue();
        assertThat(entries.findById(second).orElseThrow().isContentCompacted()).isTrue();assertThat(states.replay(second).token()).isNull();verifyNoInteractions(delivery);
    }
}
