package org.demo.returnsservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Testcontainers
class CommerceRetentionSourcePostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("source_fixture").withUsername("fixture").withPassword("fixture-only");
    static JdbcTemplate jdbc; static DriverManagerDataSource ds; static DataSourceTransactionManager manager;
    CommerceRetentionSourceOutbox outbox;
    @BeforeAll static void setup() {
        ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds); manager=new DataSourceTransactionManager(ds);
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE return_events,return_lines,return_requests,commerce_privacy_locks,account_privacy_fences,commerce_retention_change_outbox,commerce_retention_source_state RESTART IDENTITY CASCADE");
        outbox=new CommerceRetentionSourceOutbox(ds,manager);
    }
    Subject subject(long order,long version) {return new Subject(order,0,version,UUID.randomUUID());}
    void seed(long id,long order) {
        jdbc.update("INSERT INTO return_requests(id,order_id,customer_id,customer_email,request_type,reason,description,status,created_at,updated_at) VALUES(?,?,17,'fixture@example.test','RETURN','Reason','Description','REQUESTED',now(),now())",id,order);
    }
    long revision(long order) {return jdbc.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=?",Long.class,order);}
    String state() {return jdbc.queryForObject("SELECT state FROM commerce_retention_change_outbox",String.class);}
    void due() {jdbc.update("UPDATE commerce_retention_change_outbox SET retry_at=clock_timestamp()-INTERVAL '1 second'");}

    @Test void businessChangeAndNoticeCommitOrRollBackTogether() {
        outbox.bind(subject(10,0),Phase.REDACTION,0);
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(status->{seed(20,10);status.setRollbackOnly();});
        assertThat(revision(10)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_change_outbox",Long.class)).isZero();
        seed(20,10);
        assertThat(revision(10)).isEqualTo(1); assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT invalidated FROM commerce_retention_source_state",Boolean.class)).isTrue();
    }
    @Test void unboundChangesDoNotInventBindingsOrPositiveEvidence() {
        seed(20,10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_state",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_change_outbox",Long.class)).isZero();
    }
    @Test void everyParentChildMutationIsCapturedButTimestampOnlyUpdatesAreIgnored() {
        outbox.bind(subject(10,0),Phase.REDACTION,0); seed(20,10);
        jdbc.update("UPDATE return_requests SET updated_at=now()+INTERVAL '1 second' WHERE id=20");
        assertThat(revision(10)).isEqualTo(1);
        jdbc.update("UPDATE return_requests SET description='Changed' WHERE id=20");
        jdbc.update("INSERT INTO return_lines(id,return_id,order_item_id,product_id,product_name,ordered_quantity,quantity) VALUES(30,20,1,1,'Product',3,2)");
        jdbc.update("UPDATE return_lines SET quantity=1 WHERE id=30");
        jdbc.update("INSERT INTO return_events(id,return_id,revision,action,to_status,actor_ref,created_at) VALUES(40,20,1,'STATUS_CHANGED','REQUESTED','fixture-operator',now())");
        jdbc.update("UPDATE return_events SET note='Changed' WHERE id=40");
        jdbc.update("DELETE FROM return_events WHERE id=40");
        jdbc.update("DELETE FROM return_lines WHERE id=30");
        jdbc.update("DELETE FROM return_requests WHERE id=20");
        assertThat(revision(10)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_change_outbox",Long.class)).isEqualTo(1);
    }
    @Test void movingParentOrChildInvalidatesBothPurchases() {
        seed(20,10);seed(21,11);
        jdbc.update("INSERT INTO return_lines(id,return_id,order_item_id,product_id,product_name,ordered_quantity,quantity) VALUES(30,20,1,1,'Product',3,2)");
        for(long order:List.of(10L,11L,12L))outbox.bind(subject(order,0),Phase.REDACTION,0);
        jdbc.update("UPDATE return_lines SET return_id=21 WHERE id=30");
        assertThat(revision(10)).isEqualTo(1);assertThat(revision(11)).isEqualTo(1);
        jdbc.update("UPDATE return_requests SET order_id=12 WHERE id=21");
        assertThat(revision(11)).isEqualTo(2);assertThat(revision(12)).isEqualTo(1);
    }
    @Test void failedBusinessWriteDoesNotLeaveAFalseCommittedNotification() {
        outbox.bind(subject(10,0),Phase.REDACTION,0);seed(20,10);
        assertThatThrownBy(()->seed(21,10)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(revision(10)).isEqualTo(1);
    }
    @Test void bindingCompareAndSetRejectsLateCommandsAndNeverClearsSameGeneration() {
        var first=subject(10,3);var second=subject(10,4);
        assertThat(outbox.bind(first,Phase.REDACTION,0).epoch()).isEqualTo(1);seed(20,10);
        assertThat(outbox.bind(first,Phase.REDACTION,0).invalidated()).isTrue();
        assertThatThrownBy(()->outbox.bind(second,Phase.REDACTION,0)).hasMessage("RETENTION_BINDING_CONFLICT");
        assertThat(outbox.bind(second,Phase.REDACTION,1).epoch()).isEqualTo(2);
        assertThatThrownBy(()->outbox.bind(first,Phase.REDACTION,1)).hasMessage("RETENTION_BINDING_CONFLICT");
        assertThatThrownBy(()->outbox.bind(first,Phase.REDACTION,2)).hasMessage("RETENTION_BINDING_CONFLICT");
        assertThatThrownBy(()->outbox.bind(second,Phase.MINIMUM_REMOVAL,2)).hasMessage("RETENTION_BINDING_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_change_outbox",Long.class)).isEqualTo(1);
    }
    @Test void concurrentMutationCancelsOldAcknowledgementAndKeepsDirtyBindingAfterSend() {
        var subject=subject(10,0);outbox.bind(subject,Phase.REDACTION,0);seed(20,10);
        var old=outbox.claim().orElseThrow();assertThat(outbox.claim()).isEmpty();
        jdbc.update("UPDATE return_requests SET description='Concurrent' WHERE id=20");
        assertThat(outbox.acknowledge(old)).isFalse();assertThat(outbox.failed(old)).isFalse();
        var current=outbox.claim().orElseThrow();assertThat(current.revision()).isEqualTo(2);
        assertThat(outbox.acknowledge(current)).isTrue();assertThat(state()).isEqualTo("SENT");
        jdbc.update("UPDATE return_requests SET description='Later' WHERE id=20");
        assertThat(state()).isEqualTo("SENT");assertThat(outbox.bind(subject,Phase.REDACTION,0).invalidated()).isTrue();
        assertThat(outbox.claim()).isEmpty();
    }
    @Test void leaseExpiryAndRetryLimitSurviveRestartWithoutAnImplicitReset() {
        outbox.bind(subject(10,0),Phase.REDACTION,0);seed(20,10);
        var expired=outbox.claim().orElseThrow();
        jdbc.update("UPDATE commerce_retention_change_outbox SET run_until=clock_timestamp()-INTERVAL '1 second'");
        assertThat(outbox.acknowledge(expired)).isFalse();
        for(int n=2;n<=8;n++) {due();var work=new CommerceRetentionSourceOutbox(ds,manager).claim().orElseThrow();assertThat(outbox.failed(work)).isTrue();}
        due();assertThat(new CommerceRetentionSourceOutbox(ds,manager).claim()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT attempts,code,state FROM commerce_retention_change_outbox")).containsEntry("attempts",8).containsEntry("code","RETRY_LIMIT").containsEntry("state","FAILED");
    }
    @Test void abandonedLastAttemptIsRecoveredWithoutRetryingForever() {
        outbox.bind(subject(10,0),Phase.REDACTION,0);seed(20,10);
        jdbc.update("UPDATE commerce_retention_change_outbox SET attempts=7");outbox.claim().orElseThrow();
        jdbc.update("UPDATE commerce_retention_change_outbox SET run_until=clock_timestamp()-INTERVAL '1 second'");
        assertThat(outbox.claim()).isEmpty();assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_change_outbox",UUID.class)).isNull();
    }
    @Test void transportIsOutsideTheClaimTransactionAndFailureIsDurable() {
        outbox.bind(subject(10,0),Phase.REDACTION,0);seed(20,10);
        var client=mock(CommerceRetentionInvalidationClient.class);
        doAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state()).isEqualTo("LEASED");throw new IllegalStateException("fixture transport");
        }).when(client).invalidate(any(),any());
        assertThat(outbox.publishOne(client)).isTrue();assertThat(state()).isEqualTo("FAILED");
        assertThat(outbox.publishOne(client)).isFalse();due();org.mockito.Mockito.reset(client);
        assertThat(outbox.publishOne(client)).isTrue();assertThat(state()).isEqualTo("SENT");
        verify(client).invalidate(any(),eq(Phase.REDACTION));
    }
    @Test void bindingSerializesWithCommittedSourceChanges() throws Exception {
        var first=subject(10,0);outbox.bind(first,Phase.REDACTION,0);seed(20,10);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var writer=pool.submit(()->new TransactionTemplate(manager).execute(status->{jdbc.update("UPDATE return_requests SET description='Concurrent' WHERE id=20");started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var next=subject(10,1);var binder=pool.submit(()->outbox.bind(next,Phase.REDACTION,1));
            assertThatThrownBy(()->binder.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();writer.get(5,TimeUnit.SECONDS);
            assertThat(binder.get(5,TimeUnit.SECONDS).revision()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT generation FROM commerce_retention_change_outbox",UUID.class)).isEqualTo(first.chainGeneration());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void restoredSourceCannotBeReboundByAnOldOrNewCommand() {
        var first=subject(10,0);outbox.bind(first,Phase.REDACTION,0);
        seed(20,10);var work=outbox.claim().orElseThrow();
        jdbc.update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE,invalidated=TRUE,binding_epoch=binding_epoch+1,source_revision=source_revision+1");
        assertThatThrownBy(()->outbox.bind(first,Phase.REDACTION,0)).hasMessage("RETENTION_SOURCE_QUARANTINED");
        assertThatThrownBy(()->outbox.bind(subject(10,1),Phase.REDACTION,2)).hasMessage("RETENTION_SOURCE_QUARANTINED");
        assertThat(outbox.acknowledge(work)).isFalse();assertThat(outbox.failed(work)).isFalse();
        jdbc.update("UPDATE commerce_retention_change_outbox SET run_until=clock_timestamp()-interval '1 second',attempts=8");
        assertThat(outbox.claim()).isEmpty();assertThat(state()).isEqualTo("LEASED");
    }
    @Test void sourcePublisherRefusesAnOuterTransactionBeforeClaimingOrSending(){
        outbox.bind(subject(10,0),Phase.REDACTION,0);seed(20,10);
        var client=mock(CommerceRetentionInvalidationClient.class);
        new TransactionTemplate(manager).executeWithoutResult(status->assertThatThrownBy(()->outbox.publishOne(client)).hasMessage("RETENTION_INVALIDATION_CALLER_TRANSACTION"));
        verifyNoInteractions(client);assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_change_outbox",Integer.class)).isZero();assertThat(state()).isEqualTo("PENDING");
    }
}
