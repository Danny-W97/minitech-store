package org.demo.orderservice.privacy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Testcontainers
class CommerceRetentionOrderEvidencePostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("order_evidence_fixture").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionJobs jobs;CommerceRetentionOrderEvidence collector;
    @BeforeAll static void setup(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());manager=new DataSourceTransactionManager(ds);jdbc=new JdbcTemplate(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE commerce_retention_order_state,commerce_retention_bindings,commerce_retention_evidence,commerce_retention_jobs,orders RESTART IDENTITY CASCADE");jobs=new CommerceRetentionJobs(ds,manager);collector=new CommerceRetentionOrderEvidence(ds,manager);seed(10,17L);}
    void order(long id, Long customer) {
        jdbc.update("""
                INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
                delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,
                discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at)
                VALUES(?,?,'fixture@example.test',10,'CREATED','Fixture','Buyer','Fixture street','00-001','Fixture city','PL',
                '123456789','STANDARD_COURIER',0,0,'HOME_DELIVERY',?,?,?,now(),now())
                """, id, customer, "fixture-key-" + id, "b".repeat(64), customer == null ? "guest:fixture-" + id : "customer:" + customer);
    }
    void seed(long id,Long customer){order(id,customer);jdbc.update("INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) VALUES(?,1,'FIXTURE','Private product',1,10,10)",id);jdbc.update("INSERT INTO order_sagas(order_id,state,next_attempt_at,created_at,updated_at) VALUES(?,'INVENTORY_RESERVATION_PENDING',now(),now(),now())",id);}
    void close(long id){jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?",id);jdbc.update("UPDATE order_sagas SET state='FAILED',next_attempt_at=NULL,processing_token=NULL,processing_until=NULL WHERE order_id=?",id);}
    Subject closed(){jobs.prepare(10,Phase.REDACTION);close(10);return jobs.prepare(10,Phase.REDACTION);}
    Timestamp clock(){return jdbc.queryForObject("SELECT reconciled_at FROM commerce_retention_order_state WHERE order_id=10",Timestamp.class);}
    long evidence(){return jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class);}
    @Test void historicalTerminalRowsNeverBorrowCreatedOrUpdatedTime(){
        close(10);jdbc.update("UPDATE orders SET created_at=now()-interval '1000 days',updated_at=now()-interval '900 days'");var scope=jobs.prepare(10,Phase.REDACTION);
        assertThat(clock()).isNull();var result=collector.collect(scope,Phase.REDACTION);assertThat(result.state()).isEqualTo(State.UNKNOWN);assertThat(result.code()).isEqualTo(CommerceRetentionOrderEvidence.Code.MISSING_MILESTONE);
        assertThat(jdbc.queryForObject("SELECT reconciled_at FROM commerce_retention_evidence",Timestamp.class)).isNull();
    }
    @Test void trackedActualCloseCreatesOneDurableLocalProofOnly(){
        var scope=closed();assertThat(clock()).isNotNull();var result=collector.collect(scope,Phase.REDACTION);assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(evidence()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT source,kind,reconciled_at,redacted_at FROM commerce_retention_evidence")).containsEntry("source","ORDER").containsEntry("kind","CHAIN").containsEntry("reconciled_at",clock()).containsEntry("redacted_at",null);
        jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE current");var assessment=jobs.assess(jobs.claim().orElseThrow()).orElseThrow();assertThat(assessment.code()).isEqualTo(Code.MISSING_CHAIN_PROOF);assertThat(assessment.activationAllowed()).isFalse();
    }
    @Test void restartRepeatedReadsAndMetadataGenerationPreserveTheExactClock(){
        var first=closed();Timestamp time=clock();collector.collect(first,Phase.REDACTION);assertThat(collector.collect(first,Phase.REDACTION).receipt()).isEqualTo(CommerceRetentionJobs.Receipt.IDEMPOTENT);
        jobs.invalidate(10,first.chainGeneration());jobs=new CommerceRetentionJobs(ds,manager);var next=jobs.prepare(10,Phase.REDACTION);
        assertThat(new CommerceRetentionOrderEvidence(ds,manager).collect(next,Phase.REDACTION).state()).isEqualTo(State.CONFIRMED);assertThat(clock()).isEqualTo(time);
    }
    @Test void contactProductTextAndTechnicalOnlyUpdatesDoNotMoveTheMilestone(){
        closed();Timestamp time=clock();long revision=jdbc.queryForObject("SELECT process_revision FROM commerce_retention_order_state",Long.class);
        jdbc.update("UPDATE orders SET customer_email='new@example.test',delivery_date=NULL,version=version+1,updated_at=now()");jdbc.update("UPDATE order_items SET product_name='Redacted product'");jdbc.update("UPDATE order_sagas SET last_error='Safe technical note',version=version+1,updated_at=now()");
        assertThat(clock()).isEqualTo(time);assertThat(jdbc.queryForObject("SELECT process_revision FROM commerce_retention_order_state",Long.class)).isEqualTo(revision);
        assertThat(collector.collect(jobs.prepare(10,Phase.REDACTION),Phase.REDACTION).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void reopenClearsTheOldClockAndBlocksTheOldGeneration(){
        var old=closed();collector.collect(old,Phase.REDACTION);jdbc.update("UPDATE orders SET status='CONFIRMED',version=version+1");assertThat(clock()).isNull();assertThat(collector.collect(old,Phase.REDACTION).code()).isEqualTo(CommerceRetentionOrderEvidence.Code.STALE);
        var active=jobs.prepare(10,Phase.REDACTION);assertThat(collector.collect(active,Phase.REDACTION).state()).isEqualTo(State.BLOCKED);close(10);assertThat(clock()).isNotNull();
        assertThat(collector.collect(jobs.prepare(10,Phase.REDACTION),Phase.REDACTION).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void closedBusinessMutationRequiresFreshReconciliationRatherThanAnOldClock(){
        closed();jdbc.update("UPDATE order_items SET sku='CHANGED'");assertThat(clock()).isNull();assertThat(collector.collect(jobs.prepare(10,Phase.REDACTION),Phase.REDACTION).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void activeSagaLeasesAlsoBlockTheGlobalRetryGuardButAbsenceNeverConfirmsIt(){
        var scope=jobs.prepare(10,Phase.REDACTION);var result=collector.collect(scope,Phase.REDACTION);assertThat(result.state()).isEqualTo(State.BLOCKED);
        assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE kind='GUARD' AND source='NO_ACTIVE_RETRY_OR_DLQ'",String.class)).isEqualTo("BLOCKED");
        close(10);scope=jobs.prepare(10,Phase.REDACTION);collector.collect(scope,Phase.REDACTION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE generation=? AND kind='GUARD'",Long.class,scope.chainGeneration())).isZero();
    }
    @Test void inconsistentAmountsAndMissingSagaNeverBecomeConfirmed(){
        closed();jdbc.update("UPDATE order_items SET total_price=9");assertThat(clock()).isNull();assertThat(collector.collect(jobs.prepare(10,Phase.REDACTION),Phase.REDACTION).state()).isNotEqualTo(State.CONFIRMED);
        jdbc.update("DELETE FROM order_sagas");assertThat(collector.collect(jobs.prepare(10,Phase.REDACTION),Phase.REDACTION).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void completeFulfillmentRequiresTrackingDeliveryAndCollectionProof(){
        jobs.prepare(10,Phase.REDACTION);jdbc.update("UPDATE order_sagas SET state='COMPLETED',next_attempt_at=NULL");jdbc.update("UPDATE orders SET status='COMPLETED',inventory_reservation_id=1");assertThat(clock()).isNull();
        jdbc.update("UPDATE orders SET delivery_tracking_number='FIXTURE',delivery_status='DELIVERED',delivery_revision=0,deferred_payment_method='CASH'");assertThat(clock()).isNull();
        jdbc.update("UPDATE orders SET collection_reference='fixture-collection'");assertThat(clock()).isNotNull();assertThat(collector.collect(jobs.prepare(10,Phase.REDACTION),Phase.REDACTION).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void ownershipVersionPhaseAndUnknownOrdersNeverWriteEvidence(){
        var scope=closed();for(var forged:List.of(new Subject(10,18,scope.orderVersion(),scope.chainGeneration()),new Subject(10,17,scope.orderVersion()+1,scope.chainGeneration()),new Subject(11,17,0,UUID.randomUUID())))assertThat(collector.collect(forged,Phase.REDACTION).code()).isEqualTo(CommerceRetentionOrderEvidence.Code.STALE);
        assertThat(collector.collect(scope,Phase.MINIMUM_REMOVAL).code()).isEqualTo(CommerceRetentionOrderEvidence.Code.STALE);assertThat(evidence()).isZero();
    }
    @Test void guestsRemainScopedToOneOrderAndMinimumCannotInventRedactionTime(){
        seed(11,null);jobs.prepare(11,Phase.REDACTION);close(11);var guest=jobs.prepare(11,Phase.REDACTION);assertThat(guest.customerId()).isZero();assertThat(collector.collect(guest,Phase.REDACTION).state()).isEqualTo(State.CONFIRMED);
        jobs.invalidate(11,guest.chainGeneration());var minimum=jobs.prepare(11,Phase.MINIMUM_REMOVAL);assertThat(collector.collect(minimum,Phase.MINIMUM_REMOVAL).code()).isEqualTo(CommerceRetentionOrderEvidence.Code.MISSING_REDACTION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE generation=? AND state='CONFIRMED'",Long.class,minimum.chainGeneration())).isZero();
    }
    @Test void rollbackPreservesTheOpenCycleAndItsOriginalState(){
        var scope=jobs.prepare(10,Phase.REDACTION);new TransactionTemplate(manager).executeWithoutResult(status->{close(10);assertThat(clock()).isNotNull();status.setRollbackOnly();});assertThat(clock()).isNull();assertThat(jdbc.queryForObject("SELECT status FROM orders",String.class)).isEqualTo("CREATED");assertThat(collector.collect(scope,Phase.REDACTION).state()).isEqualTo(State.BLOCKED);
    }
    @Test void restoreAndManualClockRewritesCannotReleaseOrForgeAProof(){
        var scope=closed();Timestamp original=clock();for(String sql:List.of("UPDATE commerce_retention_order_state SET reconciled_at=now()-interval '1000 days'","UPDATE commerce_retention_order_state SET process_revision=999","DELETE FROM commerce_retention_order_state"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("UPDATE commerce_retention_order_state SET restore_blocked=TRUE");assertThat(collector.collect(scope,Phase.REDACTION).code()).isEqualTo(CommerceRetentionOrderEvidence.Code.QUARANTINED);assertThat(clock()).isEqualTo(original);
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_order_state SET restore_blocked=FALSE")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void untrackedBusinessRowsDoNotCreateAClockOrTrackingRecord(){
        close(10);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_order_state",Long.class)).isZero();
    }
}
