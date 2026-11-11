package org.demo.notificationservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Testcontainers
class CommerceRetentionSourcePostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("source_fixture").withUsername("fixture").withPassword("fixture-only");
    static JdbcTemplate jdbc;static DriverManagerDataSource ds;static DataSourceTransactionManager manager;
    CommerceRetentionSourceOutbox outbox;
    @BeforeAll static void setup(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE notification_dead_letters,notifications,event_identity_aliases,event_inbox,account_privacy_copy_locks,commerce_privacy_locks,account_privacy_fences,commerce_retention_change_outbox,commerce_retention_source_state RESTART IDENTITY CASCADE");outbox=new CommerceRetentionSourceOutbox(ds,manager);}
    Subject subject(long order){return new Subject(order,0,0,UUID.randomUUID());}
    void bind(long order){outbox.bind(subject(order),Phase.REDACTION,0);}
    long revision(long order){return jdbc.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=?",Long.class,order);}
    void seed(long order){jdbc.update("INSERT INTO notifications(id,event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at) VALUES(?,'PAYMENT_SUCCEEDED',?,'fixture@example.test','EMAIL','Fixture','Fixture','PENDING',now(),now())",order,order);}
    void change(){jdbc.update("UPDATE notifications SET status='SENT',sent_at=now() WHERE source_id=10");}
    @Test void businessChangeAndNoticeCommitOrRollBackTogether(){
        bind(10);new TransactionTemplate(manager).executeWithoutResult(status->{seed(10);status.setRollbackOnly();});
        assertThat(revision(10)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_change_outbox",Long.class)).isZero();
        seed(10);assertThat(revision(10)).isEqualTo(1);
        assertThatThrownBy(()->seed(10)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(revision(10)).isEqualTo(1);
    }
    @Test void unboundMutationDoesNotInventProofOrBinding(){seed(10);change();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_state",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_change_outbox",Long.class)).isZero();}
    @Test void concurrentChangeRejectsOldAcknowledgementAndSendNeverClearsDirtyState(){
        var scope=subject(10);outbox.bind(scope,Phase.REDACTION,0);seed(10);var old=outbox.claim().orElseThrow();change();
        assertThat(outbox.acknowledge(old)).isFalse();var current=outbox.claim().orElseThrow();assertThat(outbox.acknowledge(current)).isTrue();
        assertThat(outbox.bind(scope,Phase.REDACTION,0).invalidated()).isTrue();assertThat(outbox.claim()).isEmpty();
    }
    @Test void restoredBindingCannotBeReactivatedByRegistration(){
        var scope=subject(10);outbox.bind(scope,Phase.REDACTION,0);jdbc.update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE,invalidated=TRUE,binding_epoch=binding_epoch+1");
        assertThatThrownBy(()->outbox.bind(scope,Phase.REDACTION,0)).hasMessage("RETENTION_SOURCE_QUARANTINED");assertThatThrownBy(()->outbox.bind(subject(10),Phase.REDACTION,2)).hasMessage("RETENTION_SOURCE_QUARANTINED");
    }
    void other(long id,String type,long source){jdbc.update("INSERT INTO notifications(id,event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at) VALUES(?,?,?,'fixture@example.test','EMAIL','Fixture','Fixture','PENDING',now(),now())",id,type,source);}
    void deadLetter(long notification,long source){jdbc.update("INSERT INTO notification_dead_letters(notification_id,event_type,source_id,recipient,channel,subject,message,status,failure_reason,attempt_count,created_at,updated_at) VALUES(?,'PAYMENT_SUCCEEDED',?,'fixture@example.test','EMAIL','Fixture','Fixture','OPEN','Fixture',1,now(),now())",notification,source);}
    @Test void accountAndChatNumericIdentityCollisionsAreNotPurchases(){
        bind(10);other(20,"CUSTOMER_REGISTERED",10);other(21,"CHAT_REPLY_SENT",10);assertThat(revision(10)).isZero();
        jdbc.update("UPDATE notifications SET status='SENT'");jdbc.update("DELETE FROM notifications");assertThat(revision(10)).isZero();
    }
    @Test void orderScopeTransitionAndTimestampNoiseAreHandled(){
        seed(10);bind(10);bind(11);jdbc.update("UPDATE notifications SET updated_at=now()+interval '1 second'");assertThat(revision(10)).isZero();
        jdbc.update("UPDATE notifications SET source_id=11");assertThat(revision(10)).isEqualTo(1);assertThat(revision(11)).isEqualTo(1);
        jdbc.update("UPDATE notifications SET event_type='CUSTOMER_REGISTERED'");assertThat(revision(11)).isEqualTo(2);
        jdbc.update("DELETE FROM notifications");assertThat(revision(11)).isEqualTo(2);
    }
    @Test void deadLetterSnapshotsAndCanonicalOrderAreBothInvalidated(){
        seed(10);other(11,"PAYMENT_SUCCEEDED",11);bind(10);bind(11);bind(12);deadLetter(10,12);
        assertThat(revision(10)).isEqualTo(1);assertThat(revision(12)).isEqualTo(1);
        jdbc.update("UPDATE notification_dead_letters SET notification_id=11,source_id=10");
        assertThat(revision(10)).isEqualTo(2);assertThat(revision(11)).isEqualTo(1);assertThat(revision(12)).isEqualTo(2);
        jdbc.update("DELETE FROM notification_dead_letters");assertThat(revision(10)).isEqualTo(3);assertThat(revision(11)).isEqualTo(2);
    }
    @Test void acceptedInboxUsesTypedParentAndAliasesUseItsDurableOrderId(){
        seed(10);other(11,"CUSTOMER_REGISTERED",11);bind(10);bind(11);
        jdbc.update("INSERT INTO event_inbox(event_id,payload_hash,status,related_id,received_at) VALUES('order',repeat('a',64),'ACCEPTED',10,now()),('account',repeat('b',64),'ACCEPTED',11,now())");
        assertThat(revision(10)).isEqualTo(1);assertThat(revision(11)).isZero();
        jdbc.update("INSERT INTO event_identity_aliases(alias_id,canonical_id) VALUES('opaque','order')");jdbc.update("UPDATE event_identity_aliases SET canonical_id='account'");jdbc.update("DELETE FROM event_identity_aliases");
        assertThat(revision(10)).isEqualTo(3);assertThat(revision(11)).isZero();
        jdbc.update("UPDATE event_inbox SET error='Fixture' WHERE event_id='order'");
        assertThatThrownBy(()->jdbc.update("DELETE FROM event_inbox WHERE event_id='order'")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(revision(10)).isEqualTo(4);
    }
    @Test void failedBusinessWriteRollsBackTheNewSourceRevision(){
        seed(10);bind(10);assertThatThrownBy(()->jdbc.update("UPDATE notifications SET recipient=NULL")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(revision(10)).isZero();assertThat(jdbc.queryForObject("SELECT recipient FROM notifications",String.class)).isEqualTo("fixture@example.test");
    }
}

