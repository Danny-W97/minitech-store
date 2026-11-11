package org.demo.billingservice.privacy;

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
    @BeforeEach void reset(){jdbc.execute("TRUNCATE invoice_items,invoice_corrections,invoice_outbox_events,event_identity_aliases,event_inbox,invoices,commerce_privacy_locks,account_privacy_fences,commerce_retention_change_outbox,commerce_retention_source_state RESTART IDENTITY CASCADE");outbox=new CommerceRetentionSourceOutbox(ds,manager);}
    Subject subject(long order){return new Subject(order,0,0,UUID.randomUUID());}
    void bind(long order){outbox.bind(subject(order),Phase.REDACTION,0);}
    long revision(long order){return jdbc.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=?",Long.class,order);}
    void seed(long order){jdbc.update("INSERT INTO invoices(id,order_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,currency,status,created_at) VALUES(?,?,'fixture@example.test',gen_random_uuid()::text,'PERSON','Fixture','Fixture','00-001','Fixture','PL',8,2,10,'PLN','ISSUED',now())",order,order);}
    void change(){jdbc.update("UPDATE invoices SET status='CORRECTED' WHERE order_id=10");}
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
    void inbox(String id,long order){jdbc.update("INSERT INTO event_inbox(event_id,payload_hash,status,related_id,received_at) VALUES(?,repeat('a',64),'ACCEPTED',?,now())",id,order);}
    @Test void movingInvoiceItemsInvalidatesBothCanonicalOrders(){
        seed(10);seed(11);bind(10);bind(11);
        jdbc.update("INSERT INTO invoice_items(id,invoice_id,position_no,product_id,product_name,quantity,unit_price,total_price) VALUES(20,10,1,1,'Fixture',1,10,10)");
        jdbc.update("UPDATE invoice_items SET invoice_id=11 WHERE id=20");jdbc.update("UPDATE invoice_items SET quantity=2 WHERE id=20");jdbc.update("DELETE FROM invoice_items WHERE id=20");
        assertThat(revision(10)).isEqualTo(2);assertThat(revision(11)).isEqualTo(3);
    }
    @Test void correctionUsesBothDeclaredAndCanonicalOrderAndPreservesSourceDocuments(){
        seed(10);seed(11);bind(10);bind(11);bind(12);
        jdbc.update("INSERT INTO invoice_corrections(invoice_id,order_id,correction_number,reason,original_net,original_vat,original_gross,correction_net,correction_vat,correction_gross,final_net,final_vat,final_gross,created_at) VALUES(10,12,'fixture-correction','Fixture',8,2,10,0,0,0,8,2,10,now())");
        assertThat(revision(10)).isEqualTo(1);assertThat(revision(12)).isEqualTo(1);
        jdbc.update("UPDATE invoice_corrections SET invoice_id=11,order_id=10");assertThat(revision(10)).isEqualTo(2);assertThat(revision(11)).isEqualTo(1);assertThat(revision(12)).isEqualTo(2);
        jdbc.update("DELETE FROM invoice_corrections");assertThat(revision(10)).isEqualTo(3);assertThat(revision(11)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE gross_amount=10 AND buyer_name='Fixture'",Long.class)).isEqualTo(2);
    }
    @Test void existingGuardsResolveOutboxAndInboxBeforeCapture(){
        seed(10);bind(10);
        jdbc.update("INSERT INTO invoice_outbox_events(event_id,payload,status,attempts,next_attempt_at,created_at) VALUES('invoice-issued-10','{}','PENDING',0,now(),now())");
        assertThat(jdbc.queryForObject("SELECT order_id FROM invoice_outbox_events",Long.class)).isEqualTo(10);
        jdbc.update("UPDATE invoice_outbox_events SET attempts=1");jdbc.update("DELETE FROM invoice_outbox_events");
        inbox("fixture",10);jdbc.update("UPDATE event_inbox SET error='Fixture'");
        assertThatThrownBy(()->jdbc.update("DELETE FROM event_inbox")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(revision(10)).isEqualTo(5);
    }
    @Test void aliasChangesUseOnlyDurableInboxLinks(){
        inbox("a",10);inbox("b",11);bind(10);bind(11);
        jdbc.update("INSERT INTO event_identity_aliases(alias_id,canonical_id) VALUES('opaque','a')");
        jdbc.update("UPDATE event_identity_aliases SET canonical_id='b'");jdbc.update("DELETE FROM event_identity_aliases");
        assertThat(revision(10)).isEqualTo(2);assertThat(revision(11)).isEqualTo(2);
        jdbc.update("INSERT INTO event_identity_aliases(alias_id,canonical_id) VALUES('order-10','unknown')");assertThat(revision(10)).isEqualTo(2);
    }
    @Test void parentDeletionStillInvalidatesWhenInvoiceChildrenCascade(){
        seed(10);jdbc.update("INSERT INTO invoice_items(invoice_id,position_no,product_id,product_name,quantity,unit_price,total_price) VALUES(10,1,1,'Fixture',1,10,10)");bind(10);
        jdbc.update("DELETE FROM invoices");assertThat(revision(10)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_items",Long.class)).isZero();
    }
    @Test void unlinkedTechnicalHistoryRemainsUnassigned(){
        bind(10);jdbc.update("INSERT INTO event_inbox(event_id,payload_hash,status,error,received_at) VALUES('order-10',repeat('a',64),'REJECTED','Fixture',now())");
        assertThat(jdbc.queryForObject("SELECT order_id FROM event_inbox",Long.class)).isNull();assertThat(revision(10)).isZero();
    }
}

