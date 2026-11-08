package org.demo.paymentservice.privacy;

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
    @BeforeEach void reset(){jdbc.execute("TRUNCATE stripe_webhook_receipts,payment_refunds,payment_follow_ups,payment_order_gates,payment_outbox_events,payments,commerce_privacy_locks,account_privacy_fences,commerce_retention_change_outbox,commerce_retention_source_state RESTART IDENTITY CASCADE");outbox=new CommerceRetentionSourceOutbox(ds,manager);}
    Subject subject(long order){return new Subject(order,0,0,UUID.randomUUID());}
    void bind(long order){outbox.bind(subject(order),Phase.REDACTION,0);}
    long revision(long order){return jdbc.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=?",Long.class,order);}
    void seed(long order){jdbc.update("INSERT INTO payments(id,order_id,amount,currency,payment_method,status,idempotency_key,created_at,updated_at) VALUES(?,?,10,'PLN','CARD','PENDING',gen_random_uuid()::text,now(),now())",order,order);}
    void change(){jdbc.update("UPDATE payments SET status='SUCCEEDED' WHERE order_id=10");}
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
    @Test void directBusinessAndTechnicalCrudIsCapturedWithoutTimestampNoise(){
        bind(10);seed(10);jdbc.update("UPDATE payments SET updated_at=now()+interval '1 second'");assertThat(revision(10)).isEqualTo(1);change();
        jdbc.update("INSERT INTO payment_follow_ups(order_id,kind,status,created_at,updated_at) VALUES(10,'INVOICE_ISSUE','PENDING',now(),now())");
        jdbc.update("UPDATE payment_follow_ups SET status='SUCCEEDED'");jdbc.update("DELETE FROM payment_follow_ups");
        jdbc.update("INSERT INTO payment_order_gates(order_id) VALUES(10)");jdbc.update("UPDATE payment_order_gates SET cancelled=TRUE");jdbc.update("DELETE FROM payment_order_gates");
        jdbc.update("INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,next_attempt_at,created_at,updated_at) VALUES('fixture-key','PAYMENT_SUCCEEDED',10,10,'fixture@example.test','PENDING',now(),now(),now())");
        jdbc.update("UPDATE payment_outbox_events SET attempts=1");jdbc.update("DELETE FROM payment_outbox_events");
        jdbc.update("INSERT INTO stripe_webhook_receipts(event_id,event_type,payment_id,outcome,received_at) VALUES('fixture-event','fixture',10,'PROCESSED',now())");
        jdbc.update("UPDATE stripe_webhook_receipts SET outcome='IGNORED'");jdbc.update("DELETE FROM stripe_webhook_receipts");jdbc.update("DELETE FROM payments");
        assertThat(revision(10)).isEqualTo(15);
    }
    @Test void refundUsesBothDeclaredAndCanonicalParentsWithoutChangingMoney(){
        seed(10);seed(11);bind(10);bind(11);bind(12);
        jdbc.update("INSERT INTO payment_refunds(payment_id,order_id,request_key,operation_id,amount,currency,intent_id,status,automatic,reason,requested_by,created_at,updated_at) VALUES(10,12,'fixture-key','fixture-op',1,'PLN','fixture-intent','REQUESTED',false,'Fixture','fixture-operator',now(),now())");
        assertThat(revision(10)).isEqualTo(1);assertThat(revision(12)).isEqualTo(1);
        jdbc.update("UPDATE payment_refunds SET payment_id=11,order_id=10");assertThat(revision(10)).isEqualTo(2);assertThat(revision(11)).isEqualTo(1);assertThat(revision(12)).isEqualTo(2);
        jdbc.update("DELETE FROM payment_refunds");assertThat(revision(10)).isEqualTo(3);assertThat(revision(11)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM payments",Integer.class)).isEqualTo(20);
    }
    @Test void unlinkedWebhookIsNotAssignedByItsIdentifierAndMovingItInvalidatesBothParents(){
        seed(10);seed(11);bind(10);bind(11);
        jdbc.update("INSERT INTO stripe_webhook_receipts(event_id,event_type,outcome,received_at) VALUES('order-10','fixture','IGNORED',now())");assertThat(revision(10)).isZero();
        jdbc.update("UPDATE stripe_webhook_receipts SET payment_id=10");jdbc.update("UPDATE stripe_webhook_receipts SET payment_id=11");
        assertThat(revision(10)).isEqualTo(2);assertThat(revision(11)).isEqualTo(1);
    }
    @Test void anExistingPrivacyFenceCannotBeBypassedByTheNewCapture(){
        seed(10);bind(10);jdbc.update("INSERT INTO commerce_privacy_locks(order_id,customer_id,request_id) VALUES(10,17,gen_random_uuid())");
        assertThatThrownBy(this::change).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(revision(10)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payments",String.class)).isEqualTo("PENDING");
    }
}

