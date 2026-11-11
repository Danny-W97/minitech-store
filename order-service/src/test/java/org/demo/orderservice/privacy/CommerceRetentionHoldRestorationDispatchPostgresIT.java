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
import org.demo.orderservice.privacy.CommerceRetentionHoldRestorationDispatch.Code;

import org.demo.orderservice.privacy.CommerceRetentionHoldRepairAssessment.State;
import java.time.Instant;

@Testcontainers
class CommerceRetentionHoldRestorationDispatchPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("hold_restoration_preparation").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionHoldRestorationPreparation preparation;CommerceRetentionHoldRecoveryPlanning planning;CommerceRetentionHoldRepairAssessment assessment;CommerceRetentionHoldReconciliationHistory history;CommerceRetentionHoldDelivery sender;CommerceRetentionHoldIntake.Registration intake;
    @BeforeAll static void initialize(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_holds,commerce_retention_hold_scopes RESTART IDENTITY CASCADE");
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
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void committedAttemptPrecedesRpcAndSameAttemptNeverRedispatches(Participant source){
        var c=command(source,intent(source));var r=step(c,x->{assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_attempts WHERE attempt_id=?",Integer.class,x.challenge())).isEqualTo(1);return success(x);});assertThat(r.code()).isEqualTo(Code.RECORDED);assertThat(r.outcome()).isEqualTo("RESTORED");assertThat(r.currentAuthority()).isFalse();assertThat(r.executionTerminated()).isFalse();assertThat(r.activationAllowed()).isFalse();assertThat(step(c,x->{throw new AssertionError();})).isEqualTo(r);var h=dispatch().inspect(10,intake.requestId(),intake.caseReference(),source,c.intentId());assertThat(h.attempts()).hasSize(1);assertThat(h.attempts().getFirst().number()).isEqualTo(1);assertThat(h.currentAuthority()).isFalse();
    }
    @Test void unknownOutcomeAllowsOnlyNewReplayAttemptWithSameAuthorization(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));assertThat(step(c,x->{throw new IllegalStateException("sensitive-fixture");}).code()).isEqualTo(Code.PENDING);assertThat(step(c,x->{throw new AssertionError();}).code()).isEqualTo(Code.PENDING);
        assertThat(step(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.RESTORE),this::success).code()).isEqualTo(Code.REPLAY_REQUIRED);var recovery=RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY);assertThat(step(recovery,this::success).outcome()).isEqualTo("REPLAYED");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_attempts",Integer.class)).isEqualTo(2);
    }
    @Test void replayCannotBeUsedToCreateFirstAttemptOrAcknowledgeNewWrite(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));assertThat(step(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),this::success).code()).isEqualTo(Code.RECHECK_REQUIRED);step(c,x->{throw new IllegalStateException();});var r=RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY);assertThat(step(r,x->SourceHoldRestorationProtocol.reply(x,SourceHoldRestorationProtocol.Code.RESTORED,x.bindingEpoch(),x.sourceRevision()+1,RestorationCaseFixture.verifier().verify(x).id())).code()).isEqualTo(Code.PENDING);
    }
    @Test void missingOrExpiredAuthorityNeverStartsWrite(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));var bad=new SourceHoldRestorationProtocol.Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),c.bindingEpoch(),c.sourceRevision(),c.challenge(),"true",c.mode());assertThat(step(bad,this::success).code()).isEqualTo(Code.AUTHORIZATION_REQUIRED);assertThat(step(RestorationCaseFixture.authorize(c,UUID.randomUUID(),Instant.now().minusSeconds(60)),this::success).code()).isEqualTo(Code.AUTHORIZATION_REQUIRED);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_attempts",Integer.class)).isZero();
    }
    @Test void replayCannotRebindAuthorization(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));step(c,x->{throw new IllegalStateException();});assertThat(step(RestorationCaseFixture.authorize(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY)),this::success).code()).isEqualTo(Code.CONFLICT);
    }
    @ParameterizedTest @ValueSource(strings={"order","request","case","source"})
    void wrongScopeNeverReturnsHistoryOrSends(String change){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));step(c,this::success);long order=change.equals("order")?11:10;UUID request=change.equals("request")?UUID.randomUUID():intake.requestId(),ref=change.equals("case")?UUID.randomUUID():intake.caseReference();var source=change.equals("source")?Participant.RETURNS:Participant.PAYMENT;assertThat(dispatch().inspect(order,request,ref,source,c.intentId()).code()).isEqualTo(Code.UNKNOWN_INTENT);assertThat(dispatch().step(order,request,ref,source,c.intentId(),c.challenge(),c.mode(),c.authorization(),x->{throw new AssertionError();}).code()).isEqualTo(Code.UNKNOWN_INTENT);
    }
    @ParameterizedTest @ValueSource(strings={"parent","order-scope","customer-scope","order-state"})
    void quarantinePreservesPendingHistoryAndBlocksAllNewCalls(String scope){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));step(c,x->{throw new IllegalStateException();});switch(scope){case "parent"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "order-scope"->jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");case "customer-scope"->jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id,restore_blocked) VALUES('CUSTOMER',17,TRUE)");case "order-state"->jdbc.update("INSERT INTO commerce_retention_order_state(order_id,restore_blocked) VALUES(10,TRUE)");}assertThat(step(c,x->{throw new AssertionError();}).code()).isEqualTo(Code.QUARANTINED);var h=dispatch().inspect(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId());assertThat(h.code()).isEqualTo(Code.QUARANTINED);assertThat(h.attempts()).hasSize(1);assertThat(h.attempts().getFirst().outcome()).isNull();
    }
    @Test void stopIsImmutableIdempotentAndLateReplyIsNotAcknowledged(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));UUID stop=UUID.randomUUID();var r=step(c,x->{assertThat(dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId(),stop).code()).isEqualTo(Code.STOPPED);return success(x);});assertThat(r.code()).isEqualTo(Code.STOPPED);assertThat(r.outcome()).isEqualTo("ROOT_STOPPED");assertThat(dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId(),stop).code()).isEqualTo(Code.STOPPED);assertThat(dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId(),UUID.randomUUID()).code()).isEqualTo(Code.CONFLICT);assertThat(step(c,x->{throw new AssertionError();}).executionTerminated()).isFalse();assertThat(step(c,x->{throw new AssertionError();}).code()).isEqualTo(Code.STOPPED);
    }
    @Test void lateQuarantineStoresOnlyRootQuarantine(){var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));var r=step(c,x->{jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");return success(x);});assertThat(r.code()).isEqualTo(Code.QUARANTINED);assertThat(r.outcome()).isEqualTo("ROOT_QUARANTINED");}
    @Test void eightAttemptBudgetNeverResetsAndReplayUnavailableIsNotNewWritePermission(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));step(c,x->{throw new IllegalStateException();});for(int n=2;n<=8;n++){var r=step(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),x->SourceHoldRestorationProtocol.reply(x,SourceHoldRestorationProtocol.Code.REPLAY_UNAVAILABLE,0,0,null));assertThat(r.code()).isEqualTo(Code.RECORDED);assertThat(r.outcome()).isEqualTo("REPLAY_UNAVAILABLE");}assertThat(step(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),this::success).code()).isEqualTo(Code.LIMIT_REACHED);assertThat(dispatch().inspect(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId()).attempts()).hasSize(8);
    }
    @Test void databaseHistoryCannotBeMutatedOrReplayResentAsRestore(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));step(c,this::success);for(String table:List.of("attempts","results","stops")){if(table.equals("stops"))dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId(),UUID.randomUUID());for(String sql:List.of("DELETE FROM commerce_retention_hold_restoration_"+table,"UPDATE commerce_retention_hold_restoration_"+table+" SET "+(table.equals("results")?"code=code":table.equals("attempts")?"mode=mode":"stop_id=stop_id")))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    }
    @Test void sqlRejectsSkippedSequenceAndSecondRestoreWithoutTrustingCaller(){
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));step(c,x->{throw new IllegalStateException();});for(int number:List.of(2,3)){assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_restoration_attempts(intent_id,attempt_id,attempt_no,mode,scope_hash,case_authorization_id) VALUES(?,?,?,'RESTORE',?,?)",c.intentId(),UUID.randomUUID(),number,SourceHoldRestorationProtocol.scopeHash(c),RestorationCaseFixture.verifier().verify(c).id())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    }
    @Test void newPendingProbeAfterIntentBlocksInitialDispatch(){var id=intent(Participant.PAYMENT);var c=command(Participant.PAYMENT,id);put(Participant.PAYMENT,null,null);assertThat(step(c,x->{throw new AssertionError();}).code()).isEqualTo(Code.RECHECK_REQUIRED);}
    @Test void callerTransactionCannotSendOrInspectOrStop(){var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));new TransactionTemplate(manager).executeWithoutResult(tx->{assertThatThrownBy(()->step(c,this::success)).hasMessage("RETENTION_HOLD_RESTORATION_DISPATCH_CALLER_TRANSACTION");assertThatThrownBy(()->dispatch().inspect(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId())).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->dispatch().stop(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,c.intentId(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);});}
    @Test void concurrentSameAttemptIsOneRpcAndSecondReaderSeesPending()throws Exception{
        var c=command(Participant.PAYMENT,intent(Participant.PAYMENT));var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);try(var pool=Executors.newSingleThreadExecutor()){var future=pool.submit(()->step(c,x->{arrived.countDown();try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new AssertionError(e);}return success(x);}));try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();assertThat(step(c,x->{throw new AssertionError();}).code()).isEqualTo(Code.PENDING);}finally{release.countDown();}assertThat(future.get(5,TimeUnit.SECONDS).outcome()).isEqualTo("RESTORED");}assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_attempts",Integer.class)).isEqualTo(1);
    }
}
