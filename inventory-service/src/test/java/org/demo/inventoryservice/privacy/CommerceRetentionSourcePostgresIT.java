package org.demo.inventoryservice.privacy;

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
    @BeforeEach void reset(){jdbc.execute("TRUNCATE inventory_reservation_items,inventory_reservations,commerce_privacy_locks,account_privacy_fences,commerce_retention_change_outbox,commerce_retention_source_state RESTART IDENTITY CASCADE");outbox=new CommerceRetentionSourceOutbox(ds,manager);}
    Subject subject(long order){return new Subject(order,0,0,UUID.randomUUID());}
    void bind(long order){outbox.bind(subject(order),Phase.REDACTION,0);}
    long revision(long order){return jdbc.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=?",Long.class,order);}
    void seed(long order){jdbc.update("INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES(?,?,'RESERVED',now(),now(),now()+interval '1 hour')",order,order);}
    void change(){jdbc.update("UPDATE inventory_reservations SET status='CONFIRMED' WHERE order_id=10");}
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

    @Test void parentAndChildCrudAreCapturedButTimestampOnlyIsIgnored(){
        bind(10);seed(10);jdbc.update("UPDATE inventory_reservations SET updated_at=now()+interval '1 second' WHERE order_id=10");assertThat(revision(10)).isEqualTo(1);
        change();jdbc.update("INSERT INTO inventory_reservation_items(id,reservation_id,product_id,quantity) VALUES(20,10,1,2)");jdbc.update("UPDATE inventory_reservation_items SET quantity=1 WHERE id=20");jdbc.update("DELETE FROM inventory_reservation_items WHERE id=20");jdbc.update("DELETE FROM inventory_reservations WHERE id=10");assertThat(revision(10)).isEqualTo(6);
    }
    @Test void movingAChildAndItsParentInvalidatesBothOrderScopes(){
        seed(10);seed(11);jdbc.update("INSERT INTO inventory_reservation_items(id,reservation_id,product_id,quantity) VALUES(20,10,1,2)");bind(10);bind(11);bind(12);
        jdbc.update("UPDATE inventory_reservation_items SET reservation_id=11 WHERE id=20");assertThat(revision(10)).isEqualTo(1);assertThat(revision(11)).isEqualTo(1);
        jdbc.update("UPDATE inventory_reservations SET order_id=12 WHERE id=11");assertThat(revision(11)).isEqualTo(2);assertThat(revision(12)).isEqualTo(1);
    }

}
