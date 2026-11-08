package org.demo.deliveryservice.privacy;

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
    @BeforeEach void reset(){jdbc.execute("TRUNCATE shipments,commerce_privacy_locks,account_privacy_fences,commerce_retention_change_outbox,commerce_retention_source_state RESTART IDENTITY CASCADE");outbox=new CommerceRetentionSourceOutbox(ds,manager);}
    Subject subject(long order){return new Subject(order,0,0,UUID.randomUUID());}
    void bind(long order){outbox.bind(subject(order),Phase.REDACTION,0);}
    long revision(long order){return jdbc.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=?",Long.class,order);}
    void seed(long order){jdbc.update("INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,sync_state,created_at,updated_at) VALUES(?,gen_random_uuid()::text,'Fixture','COURIER','IN_TRANSIT','PENDING',now(),now())",order);}
    void change(){jdbc.update("UPDATE shipments SET status='DELIVERED' WHERE order_id=10");}
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

    @Test void shipmentAndSynchronizationChangesAreCapturedButTimestampOnlyIsIgnored(){
        bind(10);seed(10);jdbc.update("UPDATE shipments SET updated_at=now()+interval '1 second' WHERE order_id=10");assertThat(revision(10)).isEqualTo(1);
        change();jdbc.update("UPDATE shipments SET sync_state='RETRY',sync_error='FIXTURE',sync_attempts=1,sync_token='fixture-lease',sync_until=now()+interval '1 hour' WHERE order_id=10");jdbc.update("UPDATE shipments SET sync_state='SYNCED',sync_error=NULL,sync_token=NULL,sync_until=NULL WHERE order_id=10");jdbc.update("DELETE FROM shipments WHERE order_id=10");assertThat(revision(10)).isEqualTo(5);
    }
    @Test void movingShipmentInvalidatesBothOrdersWithoutChangingTrackingOrStatus(){
        seed(10);bind(10);bind(11);var tracking=jdbc.queryForObject("SELECT tracking_number FROM shipments",String.class);
        jdbc.update("UPDATE shipments SET order_id=11 WHERE order_id=10");assertThat(revision(10)).isEqualTo(1);assertThat(revision(11)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tracking_number FROM shipments",String.class)).isEqualTo(tracking);assertThat(jdbc.queryForObject("SELECT status FROM shipments",String.class)).isEqualTo("IN_TRANSIT");
    }

}
