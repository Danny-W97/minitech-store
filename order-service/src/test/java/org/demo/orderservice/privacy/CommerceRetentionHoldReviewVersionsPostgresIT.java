package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationPreparation.*;
import org.demo.orderservice.privacy.CommerceRetentionHoldReviewVersions.Code;
import org.demo.orderservice.privacy.CommerceRetentionHoldReviewVersions.Result;
import static org.demo.orderservice.privacy.CommerceRetentionHoldReviewVersions.*;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationReview.*;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationProgress.*;

import org.demo.orderservice.privacy.CommerceRetentionHoldRepairAssessment.State;
import java.time.Instant;

@Testcontainers
class CommerceRetentionHoldReviewVersionsPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("hold_restoration_preparation").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionHoldRestorationPreparation preparation;CommerceRetentionHoldRecoveryPlanning planning;CommerceRetentionHoldRepairAssessment assessment;CommerceRetentionHoldReconciliationHistory history;CommerceRetentionHoldDelivery sender;CommerceRetentionHoldIntake.Registration intake;
    @BeforeAll static void initialize(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_holds,commerce_retention_hold_scopes,account_privacy_fences RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES(10,17,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY','fixture-10',?,'fixture',now(),now())","a".repeat(64));
        intake=new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        preparation=new CommerceRetentionHoldRestorationPreparation(ds,manager);planning=new CommerceRetentionHoldRecoveryPlanning(ds,manager);assessment=new CommerceRetentionHoldRepairAssessment(ds,manager);history=new CommerceRetentionHoldReconciliationHistory(ds,manager);sender=new CommerceRetentionHoldDelivery(ds,manager);
        for(var p:CommerceRetentionSourceHolds.SOURCES)sender.deliver(10,intake.requestId(),intake.caseReference(),p,c->new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,UUID.randomUUID()));
    }
    UUID put(Participant p,CommerceRetentionHoldReconciliation.Code code,Instant time){
        int n=jdbc.queryForObject("SELECT count(*)+1 FROM commerce_retention_hold_probes WHERE request_id=? AND source=?",Integer.class,intake.requestId(),p.name());UUID probe=UUID.randomUUID();
        jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) SELECT request_id,source,?,?,source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source=?",n,probe,intake.requestId(),p.name());
        if(code!=null){boolean observed=Set.of(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,CommerceRetentionHoldReconciliation.Code.SOURCE_CONFLICT,CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN,CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED).contains(code);boolean versioned=observed && code!=CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN;
            jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,?,?,?,?)",probe,code.name(),observed?java.sql.Timestamp.from(time):null,versioned?2:0,versioned?3:0);
        }return probe;
    }
    UUID intent(Participant source){put(source,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());UUID id=UUID.randomUUID();assertThat(planning.prepare(10,intake.requestId(),intake.caseReference(),source,id).code()).isEqualTo(CommerceRetentionHoldRecoveryPlanning.Code.PREPARED);return id;}
    Prepared prepare(Participant source,UUID intent,SourceHoldRestorationProtocol.Mode mode){return preparation.prepare(10,intake.requestId(),intake.caseReference(),source,intent,UUID.randomUUID(),"fixture-sensitive-authorization",mode);}

    CommerceRetentionHoldRestorationDispatch dispatch(){return new CommerceRetentionHoldRestorationDispatch(ds,manager,RestorationCaseFixture.verifier());}
    SourceHoldRestorationProtocol.Command command(Participant p,UUID id){return RestorationCaseFixture.authorize(preparation.prepare(10,intake.requestId(),intake.caseReference(),p,id,UUID.randomUUID(),"",SourceHoldRestorationProtocol.Mode.RESTORE).command());}
    CommerceRetentionHoldRestorationDispatch.Result step(SourceHoldRestorationProtocol.Command c,CommerceRetentionHoldRestorationDispatch.Transport remote){return dispatch().step(10,intake.requestId(),intake.caseReference(),c.delivery().source(),c.intentId(),c.challenge(),c.mode(),c.authorization(),remote);}
    SourceHoldRestorationProtocol.Reply success(SourceHoldRestorationProtocol.Command c){assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return SourceHoldRestorationProtocol.reply(c,c.mode()==SourceHoldRestorationProtocol.Mode.RESTORE?SourceHoldRestorationProtocol.Code.RESTORED:SourceHoldRestorationProtocol.Code.REPLAYED,c.bindingEpoch(),c.sourceRevision()+1,RestorationCaseFixture.verifier().verify(c).id());}


    static void awaitReviewClock(UUID intent){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp()>GREATEST(i.created_at,COALESCE((SELECT max(created_at) FROM commerce_retention_hold_restoration_attempts WHERE intent_id=i.intent_id),'epoch'::timestamptz),COALESCE((SELECT max(r.recorded_at) FROM commerce_retention_hold_restoration_results r JOIN commerce_retention_hold_restoration_attempts a USING(attempt_id) WHERE a.intent_id=i.intent_id),'epoch'::timestamptz))+interval '20 milliseconds' FROM commerce_retention_hold_repair_intents i WHERE i.intent_id=?",Boolean.class,intent))){
            assertThat(System.nanoTime()).as("Fixture database clock must pass prior repair metadata").isLessThan(deadline);
            try{Thread.sleep(2);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        }
    }
    CommerceRetentionHoldRestorationProgress progress(){return new CommerceRetentionHoldRestorationProgress(ds,manager);}
    Progress assess(UUID id){return progress().assess(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,id);}
    Refresh refresh(Participant source,UUID id,UUID probe,CommerceRetentionHoldReconciliation.Transport remote){awaitReviewClock(id);return progress().refresh(10,intake.requestId(),intake.caseReference(),source,id,probe,remote);}
    SourceHoldInspectionProtocol.Reply match(SourceHoldInspectionProtocol.Command c){assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new SourceHoldInspectionProtocol.Reply(c,SourceHoldInspectionProtocol.Code.MATCH,Instant.now(),2,4);}
    UUID acknowledged(Participant source){UUID id=intent(source);assertThat(step(command(source,id),this::success).outcome()).isEqualTo("RESTORED");return id;}

    CommerceRetentionHoldRestorationReview aggregate(){return new CommerceRetentionHoldRestorationReview(ds,manager);}
    Review review(){return aggregate().review(10,intake.requestId(),intake.caseReference());}
    Source source(Review r,Participant p){return r.sources().stream().filter(s->s.source()==p).findFirst().orElseThrow();}
    UUID matched(Participant p){UUID id=acknowledged(p);refresh(p,id,UUID.randomUUID(),this::match);return id;}

    CommerceRetentionHoldReviewVersions versions(){return new CommerceRetentionHoldReviewVersions(ds,manager);}
    Result capture(UUID id){return versions().capture(10,intake.requestId(),intake.caseReference(),id);}
    Result capture(){return capture(UUID.randomUUID());}
    Result check(Snapshot s){awaitVersionClock(s.reviewId());return versions().check(10,intake.requestId(),intake.caseReference(),s.reviewId(),s.versionHash());}
    static void awaitVersionClock(UUID id){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp()>reviewed_at+interval '20 milliseconds' FROM commerce_retention_hold_review_versions WHERE review_id=?",Boolean.class,id))){assertThat(System.nanoTime()).isLessThan(deadline);try{Thread.sleep(2);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}}
    @Test void captureAndCheckUseServerRecordAndNeverGrantExecutionAuthority(){var r=capture();assertThat(r.code()).isEqualTo(Code.CAPTURED);assertThat(r.snapshot().ordinal()).isEqualTo(1);assertThat(r.snapshot().versionHash()).matches("[0-9a-f]{64}");assertThat(r.snapshot().expiresAt()).isAfter(r.snapshot().reviewedAt()).isBeforeOrEqualTo(r.snapshot().reviewedAt().plusSeconds(15));assertThat(r.review().sources()).hasSize(7);var c=check(r.snapshot());assertThat(c.code()).isEqualTo(Code.CURRENT_LOCAL);assertThat(c.diagnosticOnly()).isTrue();assertThat(c.currentAuthority()).isFalse();assertThat(c.completeCaseCoverage()).isFalse();assertThat(c.repairExecutionAllowed()).isFalse();assertThat(c.executionTerminated()).isFalse();assertThat(c.activationAllowed()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_versions",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probes",Integer.class)).isZero();}
    @Test void sameIdRetryIsHistoryOnlyWithoutRenewingLeaseOrRecomputingPreview(){UUID id=UUID.randomUUID();var first=capture(id);var repeated=capture(id);assertThat(repeated.code()).isEqualTo(Code.ALREADY_CAPTURED);assertThat(repeated.snapshot()).isEqualTo(first.snapshot());assertThat(repeated.review()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_versions",Integer.class)).isEqualTo(1);}
    @Test void anotherCaptureDoesNotInvalidateUnchangedEvidenceVersion(){var first=capture();var second=capture();assertThat(second.snapshot().ordinal()).isEqualTo(2);assertThat(second.snapshot().versionHash()).isEqualTo(first.snapshot().versionHash());assertThat(check(first.snapshot()).code()).isEqualTo(Code.CURRENT_LOCAL);}
    @ParameterizedTest @ValueSource(strings={"order","request","case"})
    void foreignScopeNeverExposesVersionOrPreview(String change){var s=capture().snapshot();var r=versions().check(change.equals("order")?11:10,change.equals("request")?UUID.randomUUID():intake.requestId(),change.equals("case")?UUID.randomUUID():intake.caseReference(),s.reviewId(),s.versionHash());assertThat(r.code()).isEqualTo(Code.UNKNOWN_REQUEST);assertThat(r.snapshot()).isNull();assertThat(r.review()).isNull();}
    @Test void unknownIdAndWrongExpectedVersionAreRejected(){var s=capture().snapshot();assertThat(versions().check(10,intake.requestId(),intake.caseReference(),UUID.randomUUID(),s.versionHash()).code()).isEqualTo(Code.UNKNOWN_REVIEW);assertThat(versions().check(10,intake.requestId(),intake.caseReference(),s.reviewId(),"f".repeat(64)).code()).isEqualTo(Code.VERSION_MISMATCH);}
    @Test void orderVersionChangeInvalidatesScopeEvenWhenReviewSummaryIsTheSame(){var s=capture().snapshot();jdbc.update("UPDATE orders SET version=version+1 WHERE id=10");assertThat(check(s).code()).isEqualTo(Code.SCOPE_CHANGED);}
    @Test void knownHoldRevisionInvalidatesScope(){var s=capture().snapshot();new CommerceRetentionHolds(ds,manager).placeOrder(10,UUID.randomUUID(),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionHolds.Reason.OPEN_CASE);assertThat(check(s).code()).isEqualTo(Code.SCOPE_CHANGED);}
    @Test void accountFenceInvalidatesScope(){var s=capture().snapshot();jdbc.update("INSERT INTO account_privacy_fences(customer_id,request_id,state) VALUES(17,?,'BLOCKED')",UUID.randomUUID());assertThat(check(s).code()).isEqualTo(Code.SCOPE_CHANGED);}
    @Test void newOriginalDeliveryAttemptInvalidatesHistoryWithoutChangingSummary(){var s=capture().snapshot();jdbc.update("INSERT INTO commerce_retention_hold_delivery_attempts(request_id,source,attempt_no,attempt_id) VALUES(?,'PAYMENT',2,?)",intake.requestId(),UUID.randomUUID());assertThat(check(s).code()).isEqualTo(Code.HISTORY_CHANGED);}
    @Test void newProbeOrIntentInvalidatesHistory(){var s=capture().snapshot();put(Participant.PAYMENT,null,null);assertThat(check(s).code()).isEqualTo(Code.HISTORY_CHANGED);}
    @Test void newRepairIntentInvalidatesHistory(){var s=capture().snapshot();intent(Participant.PAYMENT);assertThat(check(s).code()).isEqualTo(Code.HISTORY_CHANGED);}
    @Test void changedRawNegativeResultIsDetectedEvenWhenProjectionIsUnchanged(){UUID id=intent(Participant.PAYMENT);step(command(Participant.PAYMENT,id),c->SourceHoldRestorationProtocol.reply(c,SourceHoldRestorationProtocol.Code.STALE_SOURCE,0,0,null));var s=capture().snapshot();jdbc.execute("ALTER TABLE commerce_retention_hold_restoration_results DISABLE TRIGGER USER");try{jdbc.update("UPDATE commerce_retention_hold_restoration_results SET code='CONFLICT'");}finally{jdbc.execute("ALTER TABLE commerce_retention_hold_restoration_results ENABLE TRIGGER USER");}assertThat(check(s).code()).isEqualTo(Code.HISTORY_CHANGED);}
    @Test void stopAndReadResolutionCannotReusePreviousVersion(){UUID id=matched(Participant.PAYMENT);var s=capture().snapshot();dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,id,UUID.randomUUID());assertThat(check(s).code()).isEqualTo(Code.HISTORY_CHANGED);}
    @Test void readSupersessionInvalidatesHistoryWithoutInventingItsResult(){UUID pending=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());var s=capture().snapshot();planning.supersede(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,pending,replacement,UUID.randomUUID());assertThat(check(s).code()).isEqualTo(Code.HISTORY_CHANGED);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probe_results WHERE probe_id=?",Integer.class,pending)).isZero();}
    @ParameterizedTest @ValueSource(strings={"parent","order-scope","customer-scope","order-state"})
    void quarantineRejectsCheckAndCaptureWithoutRenewal(String change){var s=capture().snapshot();switch(change){case "parent"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "order-scope"->jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");case "customer-scope"->jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id,restore_blocked) VALUES('CUSTOMER',17,TRUE)");case "order-state"->jdbc.update("INSERT INTO commerce_retention_order_state(order_id,restore_blocked) VALUES(10,TRUE)");}assertThat(check(s).code()).isEqualTo(Code.QUARANTINED);assertThat(capture(s.reviewId()).code()).isEqualTo(Code.QUARANTINED);assertThat(capture().code()).isEqualTo(Code.QUARANTINED);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_versions",Integer.class)).isEqualTo(1);}
    @ParameterizedTest @ValueSource(strings={"expired","future"})
    void expiredLeaseOrClockRollbackCannotBeRenewedByClient(String change){var s=capture().snapshot();jdbc.execute("ALTER TABLE commerce_retention_hold_review_versions DISABLE TRIGGER USER");try{jdbc.update("WITH c AS (SELECT clock_timestamp() AS t) UPDATE commerce_retention_hold_review_versions SET reviewed_at=c.t+interval '"+(change.equals("expired")?"-30":"10")+" seconds',expires_at=c.t+interval '"+(change.equals("expired")?"-15":"25")+" seconds' FROM c");}finally{jdbc.execute("ALTER TABLE commerce_retention_hold_review_versions ENABLE TRIGGER USER");}var r=versions().check(10,intake.requestId(),intake.caseReference(),s.reviewId(),s.versionHash());assertThat(r.code()).isEqualTo(change.equals("expired")?Code.EXPIRED:Code.CLOCK_CHANGED);assertThat(capture(s.reviewId()).code()).isEqualTo(Code.ALREADY_CAPTURED);}
    @Test void corruptedServerFingerprintCannotBeAcceptedOrReturnedAsAnIntactRetry(){var s=capture().snapshot();jdbc.execute("ALTER TABLE commerce_retention_hold_review_versions DISABLE TRIGGER USER");try{jdbc.update("UPDATE commerce_retention_hold_review_versions SET result_hash=repeat('b',64)");}finally{jdbc.execute("ALTER TABLE commerce_retention_hold_review_versions ENABLE TRIGGER USER");}assertThat(check(s).code()).isEqualTo(Code.INCONSISTENT_HISTORY);assertThat(capture(s.reviewId()).code()).isEqualTo(Code.INCONSISTENT_HISTORY);}
    @Test void immutableHistoryAndEightCaptureBudgetNeverReset(){var first=capture();for(int n=2;n<=8;n++)assertThat(capture().snapshot().ordinal()).isEqualTo(n);assertThat(capture().code()).isEqualTo(Code.LIMIT_REACHED);assertThat(capture(first.snapshot().reviewId()).snapshot()).isEqualTo(first.snapshot());for(String sql:List.of("DELETE FROM commerce_retention_hold_review_versions","UPDATE commerce_retention_hold_review_versions SET version_hash=version_hash"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_versions",Integer.class)).isEqualTo(8);}
    @Test void concurrentSameCaptureIdStoresOneReceipt()throws Exception{UUID id=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->capture(id));var b=pool.submit(()->capture(id));var x=a.get(8,TimeUnit.SECONDS);var y=b.get(8,TimeUnit.SECONDS);assertThat(List.of(x.code(),y.code())).containsExactlyInAnyOrder(Code.CAPTURED,Code.ALREADY_CAPTURED);assertThat(x.snapshot()).isEqualTo(y.snapshot());}assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_versions",Integer.class)).isEqualTo(1);}
    @Test void callerTransactionAndUnscopedPackageSnapshotAreRejected(){new TransactionTemplate(manager).executeWithoutResult(tx->{assertThatThrownBy(this::capture).hasMessage("RETENTION_HOLD_REVIEW_VERSION_CALLER_TRANSACTION");assertThatThrownBy(()->versions().check(10,intake.requestId(),intake.caseReference(),UUID.randomUUID(),"a".repeat(64))).isInstanceOf(IllegalStateException.class);});assertThatThrownBy(()->aggregate().snapshot(10,intake.requestId(),intake.caseReference())).hasMessage("RESTORATION_REVIEW_SNAPSHOT_REQUIRES_TRANSACTION");}
    @Test void sqlFailureGivesNoVersionOrPreview(){var unavailable=org.mockito.Mockito.mock(javax.sql.DataSource.class);try{org.mockito.Mockito.when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("fixture-private"));}catch(java.sql.SQLException impossible){throw new AssertionError(impossible);}var r=new CommerceRetentionHoldReviewVersions(unavailable,new DataSourceTransactionManager(unavailable)).capture(10,intake.requestId(),intake.caseReference(),UUID.randomUUID());assertThat(r.code()).isEqualTo(Code.UNAVAILABLE);assertThat(r.snapshot()).isNull();assertThat(r.review()).isNull();assertThat(r.toString()).doesNotContain("fixture-private");}
}
