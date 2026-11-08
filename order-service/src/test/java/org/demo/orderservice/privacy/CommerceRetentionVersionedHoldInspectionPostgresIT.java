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
class CommerceRetentionVersionedHoldInspectionPostgresIT {
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

    CommerceRetentionVersionedHoldInspection inspector(){return new CommerceRetentionVersionedHoldInspection(ds,manager);}
    CommerceRetentionVersionedHoldInspection.Result inspect(Snapshot v,Participant p,UUID intent,UUID step,CommerceRetentionHoldReconciliation.Transport remote){awaitVersionClock(v.reviewId());return inspector().inspect(10,intake.requestId(),intake.caseReference(),v.reviewId(),v.versionHash(),p,intent,step,remote);}
    int steps(){return jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_steps",Integer.class);}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"RETURNS","INVENTORY","PROMOTION","DELIVERY","PAYMENT","BILLING","NOTIFICATION"})
    void checkedReservationCommitsBeforeOneReadAndRetryIsHistoryOnly(Participant p){
        UUID intent=intent(p),id=UUID.randomUUID();var v=capture().snapshot();AtomicInteger calls=new AtomicInteger();
        var r=inspect(v,p,intent,id,c->{calls.incrementAndGet();assertThat(steps()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT expires_at FROM commerce_retention_hold_review_steps",java.sql.Timestamp.class).toInstant()).isEqualTo(v.expiresAt());assertThat(c.challenge()).isEqualTo(id);return match(c);});
        assertThat(r.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.RECORDED);assertThat(r.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);
        assertThat(inspect(v,p,intent,id,c->{throw new AssertionError("must not resend");})).isEqualTo(r);assertThat(calls.get()).isEqualTo(1);
        assertThat(r.activationAllowed()).isFalse();assertThat(r.repairExecutionAllowed()).isFalse();assertThat(r.currentAuthority()).isFalse();assertThat(r.completeCaseCoverage()).isFalse();assertThat(r.executionTerminated()).isFalse();
        assertThat(check(v).code()).isEqualTo(Code.HISTORY_CHANGED);
    }
    @ParameterizedTest @ValueSource(strings={"order","history","version","quarantine","expired","future"})
    void outdatedReviewNeverReservesOrCalls(String change){
        UUID intent=intent(Participant.PAYMENT);var v=capture().snapshot();
        switch(change){case "order"->jdbc.update("UPDATE orders SET version=version+1 WHERE id=10");case "history"->put(Participant.BILLING,null,null);case "quarantine"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "expired","future"->{jdbc.execute("ALTER TABLE commerce_retention_hold_review_versions DISABLE TRIGGER USER");try{jdbc.update("WITH t AS(SELECT clock_timestamp() t) UPDATE commerce_retention_hold_review_versions SET reviewed_at=t.t+interval '"+(change.equals("expired")?"-30":"10")+" seconds',expires_at=t.t+interval '"+(change.equals("expired")?"-15":"25")+" seconds' FROM t");}finally{jdbc.execute("ALTER TABLE commerce_retention_hold_review_versions ENABLE TRIGGER USER");}}}
        var r=inspector().inspect(10,intake.requestId(),intake.caseReference(),v.reviewId(),change.equals("version")?"f".repeat(64):v.versionHash(),Participant.PAYMENT,intent,UUID.randomUUID(),c->{throw new AssertionError();});
        assertThat(r.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.REJECTED);assertThat(steps()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probes",Integer.class)).isEqualTo(change.equals("history")?2:1);
    }
    @ParameterizedTest @ValueSource(strings={"order","history","stop","quarantine","expired","future"})
    void changeDuringReadDiscardsPositiveResponse(String change){
        UUID intent=intent(Participant.PAYMENT),id=UUID.randomUUID();var v=capture().snapshot();
        var r=inspect(v,Participant.PAYMENT,intent,id,c->{var reply=match(c);switch(change){case "order"->jdbc.update("UPDATE orders SET version=version+1 WHERE id=10");case "history"->put(Participant.BILLING,null,null);case "stop"->dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,intent,UUID.randomUUID());case "quarantine"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "expired","future"->{jdbc.execute("ALTER TABLE commerce_retention_hold_review_steps DISABLE TRIGGER USER");try{jdbc.update("UPDATE commerce_retention_hold_review_steps SET "+(change.equals("expired")?"expires_at=clock_timestamp()-interval '1 second'":"created_at=clock_timestamp()+interval '10 seconds'"));}finally{jdbc.execute("ALTER TABLE commerce_retention_hold_review_steps ENABLE TRIGGER USER");}}}return reply;});
        assertThat(r.code()).isIn(CommerceRetentionVersionedHoldInspection.Code.DATA_CHANGED,CommerceRetentionVersionedHoldInspection.Code.QUARANTINED,CommerceRetentionVersionedHoldInspection.Code.STOPPED,CommerceRetentionVersionedHoldInspection.Code.EXPIRED,CommerceRetentionVersionedHoldInspection.Code.CLOCK_CHANGED);
        var stored=inspect(v,Participant.PAYMENT,intent,id,c->{throw new AssertionError();});assertThat(stored.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.RECORDED);assertThat(stored.outcome().code()).isIn(CommerceRetentionHoldReconciliation.Code.UNAVAILABLE,CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED);assertThat(stored.outcome().observedAt()).isNull();
    }
    @Test void foreignIntentAndScopeNeverReserve(){UUID intent=intent(Participant.PAYMENT);var v=capture().snapshot();assertThat(inspect(v,Participant.BILLING,intent,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.CONFLICT);assertThat(inspector().inspect(10,intake.requestId(),UUID.randomUUID(),v.reviewId(),v.versionHash(),Participant.PAYMENT,intent,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.UNKNOWN_REQUEST);assertThat(steps()).isZero();}
    @Test void stoppedIntentDoesNotReserveEvenWithCurrentLocalVersion(){UUID intent=intent(Participant.PAYMENT);dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,intent,UUID.randomUUID());var v=capture().snapshot();assertThat(inspect(v,Participant.PAYMENT,intent,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.STOPPED);assertThat(steps()).isZero();}
    @Test void exhaustedProbeBudgetIsNotResetByNewVersion(){UUID intent=intent(Participant.PAYMENT);for(int n=1;n<8;n++)put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.UNAVAILABLE,null);var v=capture().snapshot();assertThat(inspect(v,Participant.PAYMENT,intent,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.LIMIT_REACHED);assertThat(steps()).isZero();}
    @Test void failedStepInsertRollsBackItsProbe(){UUID intent=intent(Participant.PAYMENT);var v=capture().snapshot();jdbc.execute("CREATE FUNCTION fixture_fail_step() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'fixture failure'; END;$$");jdbc.execute("CREATE TRIGGER fixture_fail_step BEFORE INSERT ON commerce_retention_hold_review_steps FOR EACH ROW EXECUTE FUNCTION fixture_fail_step()");try{assertThat(inspect(v,Participant.PAYMENT,intent,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.UNAVAILABLE);assertThat(steps()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probes",Integer.class)).isEqualTo(1);}finally{jdbc.execute("DROP TRIGGER fixture_fail_step ON commerce_retention_hold_review_steps");jdbc.execute("DROP FUNCTION fixture_fail_step()");}}
    @Test void immutableSqlBindingCannotBeReusedOrDeleted(){UUID intent=intent(Participant.PAYMENT),id=UUID.randomUUID();var v=capture().snapshot();inspect(v,Participant.PAYMENT,intent,id,this::match);for(String sql:List.of("DELETE FROM commerce_retention_hold_review_steps","UPDATE commerce_retention_hold_review_steps SET expires_at=expires_at"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(inspect(v,Participant.PAYMENT,intent,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.REJECTED);assertThat(steps()).isEqualTo(1);}
    @Test void callerTransactionsAndUnscopedPackageFramesAreRejected(){new TransactionTemplate(manager).executeWithoutResult(s->assertThatThrownBy(()->inspector().inspect(10,intake.requestId(),intake.caseReference(),UUID.randomUUID(),"a".repeat(64),Participant.PAYMENT,UUID.randomUUID(),UUID.randomUUID(),c->{throw new AssertionError();})).hasMessage("VERSIONED_HOLD_INSPECTION_CALLER_TRANSACTION"));assertThatThrownBy(()->versions().frame(10,intake.requestId(),intake.caseReference())).hasMessage("HOLD_REVIEW_VERSION_REQUIRES_TRANSACTION");assertThatThrownBy(()->versions().checkLocked(10,intake.requestId(),intake.caseReference(),UUID.randomUUID(),"a".repeat(64))).hasMessage("HOLD_REVIEW_VERSION_REQUIRES_TRANSACTION");}
    @Test void changeAfterReservationCommitPreventsRpc(){
        UUID intent=intent(Participant.PAYMENT),id=UUID.randomUUID();var v=capture().snapshot();awaitVersionClock(v.reviewId());var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        var data=new DelegatingDataSource(ds){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{var c=ds.getConnection();boolean[] target={false};return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,a)->{if(m.getName().equals("prepareStatement") && a[0] instanceof String sql && sql.contains("INSERT INTO commerce_retention_hold_review_steps("))target[0]=true;Object r;try{r=m.invoke(c,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}if(m.getName().equals("commit") && target[0] && once.compareAndSet(true,false)){try(var other=ds.getConnection();var q=other.createStatement()){q.executeUpdate("UPDATE orders SET version=version+1 WHERE id=10");}}return r;});}};
        var r=new CommerceRetentionVersionedHoldInspection(data,new DataSourceTransactionManager(data)).inspect(10,intake.requestId(),intake.caseReference(),v.reviewId(),v.versionHash(),Participant.PAYMENT,intent,id,c->{throw new AssertionError("must reject before RPC");});
        assertThat(once.get()).isFalse();assertThat(r.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.DATA_CHANGED);assertThat(steps()).isEqualTo(1);assertThat(inspect(v,Participant.PAYMENT,intent,id,c->{throw new AssertionError();}).outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNAVAILABLE);
    }
    @Test void supersededPendingReadCannotAppendLatePositive(){
        UUID intent=intent(Participant.PAYMENT),id=UUID.randomUUID();var v=capture().snapshot();
        var r=inspect(v,Participant.PAYMENT,intent,id,c->{var reply=match(c);UUID replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());assertThat(planning.supersede(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,id,replacement,UUID.randomUUID()).code()).isEqualTo(CommerceRetentionHoldRecoveryPlanning.Code.RECORDED);return reply;});
        assertThat(r.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.SUPERSEDED);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probe_results WHERE probe_id=?",Integer.class,id)).isZero();
    }

}
