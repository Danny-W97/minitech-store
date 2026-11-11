package org.demo.orderservice.privacy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import org.demo.orderservice.privacy.CommerceRetentionIntents.Code;
import org.demo.orderservice.privacy.CommerceRetentionIntents.State;

@Testcontainers
class CommerceRetentionIntentsPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("retention_intents").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static DataSourceTransactionManager manager;static JdbcTemplate jdbc;
    CommerceRetentionIntents intents;CommerceRetentionJobs jobs;Subject subject;UUID id;
    @BeforeAll static void migrate(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());manager=new DataSourceTransactionManager(ds);jdbc=new JdbcTemplate(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_jobs,commerce_retention_hold_scopes,commerce_retention_holds,account_privacy_fences,commerce_privacy_locks RESTART IDENTITY CASCADE");
        intents=new CommerceRetentionIntents(ds,manager);jobs=new CommerceRetentionJobs(ds,manager);
        order(10,17L);order(11,18L);order(12,null);subject=jobs.prepare(10,Phase.REDACTION);id=UUID.randomUUID();
    }
    void order(long order,Long customer){jdbc.update("""
        INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
        delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,
        discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at)
        VALUES(?,?,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,
        0,'HOME_DELIVERY',?,?,?,now(),now())
        """,order,customer,"fixture-"+order,"a".repeat(64),customer==null?"guest:fixture":"customer:"+customer);}
    CommerceRetentionIntents.Result prepare(){return intents.prepare(subject,Phase.REDACTION,id);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @Test void preparationIsDurableBoundedAndNeverAnExecutionPermit(){
        var result=new CommerceRetentionWorkflow(ds,manager).prepareIntent(subject,Phase.REDACTION,id);
        assertThat(result.code()).isEqualTo(Code.PREPARED);assertThat(result.intent().state()).isEqualTo(State.WAITING_AUTHORIZATION);
        assertThat(result.activationAllowed()).isFalse();assertThat(result.executionTerminated()).isFalse();
        assertThat(new CommerceRetentionIntents(ds,manager).prepare(subject,Phase.REDACTION,id).code()).isEqualTo(Code.IDEMPOTENT);
        assertThat(intents.prepare(subject,Phase.REDACTION,UUID.randomUUID()).code()).isEqualTo(Code.CONFLICT);
        assertThat(count("commerce_retention_intents")).isEqualTo(1);assertThat(count("commerce_retention_intent_events")).isEqualTo(1);
        assertThat(new CommerceRetentionRedactionWorker(ds,manager).redact(subject,Instant.now()).code()).isEqualTo("RETENTION_EXECUTION_NOT_CONFIGURED");
        assertThat(jdbc.queryForMap("SELECT customer_email,privacy_redacted FROM orders WHERE id=10")).containsEntry("customer_email","fixture@example.test").containsEntry("privacy_redacted",false);
    }
    @Test void stopAndCancellationAreRevisionedIdempotentMetadataNotWorkerTermination(){
        prepare();var stop=UUID.randomUUID();var cancel=UUID.randomUUID();
        var requested=new CommerceRetentionWorkflow(ds,manager).requestIntentStop(subject,Phase.REDACTION,id,0,stop);
        assertThat(requested.code()).isEqualTo(Code.UPDATED);assertThat(requested.intent().state()).isEqualTo(State.STOP_REQUESTED);
        assertThat(requested.executionTerminated()).isFalse();
        var cancelled=intents.cancelUnstarted(subject,Phase.REDACTION,id,1,cancel);
        assertThat(cancelled.intent().state()).isEqualTo(State.CANCELLED);assertThat(cancelled.intent().revision()).isEqualTo(2);
        assertThat(cancelled.executionTerminated()).isFalse();assertThat(cancelled.activationAllowed()).isFalse();
        assertThat(intents.requestStop(subject,Phase.REDACTION,id,0,stop).code()).isEqualTo(Code.IDEMPOTENT);
        assertThat(intents.cancelUnstarted(subject,Phase.REDACTION,id,1,cancel).code()).isEqualTo(Code.IDEMPOTENT);
        assertThat(count("commerce_retention_intent_events")).isEqualTo(3);
    }
    @Test void staleRevisionAndCrossActionRequestReuseCannotAddTransitions(){
        prepare();var request=UUID.randomUUID();
        assertThat(intents.requestStop(subject,Phase.REDACTION,id,1,request).code()).isEqualTo(Code.CONFLICT);
        assertThat(intents.requestStop(subject,Phase.REDACTION,id,0,request).code()).isEqualTo(Code.UPDATED);
        assertThat(intents.cancelUnstarted(subject,Phase.REDACTION,id,1,request).code()).isEqualTo(Code.CONFLICT);
        assertThat(count("commerce_retention_intent_events")).isEqualTo(2);
    }
    @Test void cancellationFromWaitingIsFinalWithoutUnboundedRepeatEvents(){
        prepare();assertThat(intents.cancelUnstarted(subject,Phase.REDACTION,id,0,UUID.randomUUID()).code()).isEqualTo(Code.UPDATED);
        for(int n=0;n<5;n++)assertThat(intents.requestStop(subject,Phase.REDACTION,id,0,UUID.randomUUID()).code()).isEqualTo(Code.UNCHANGED);
        assertThat(count("commerce_retention_intent_events")).isEqualTo(2);
    }
    @Test void foreignOwnerPhaseAndGenerationCannotBorrowAnIntent(){
        prepare();var other=jobs.prepare(11,Phase.REDACTION);
        assertThat(intents.prepare(other,Phase.REDACTION,id).code()).isEqualTo(Code.CONFLICT);
        for(var wrong:List.of(new Subject(10,18,subject.orderVersion(),subject.chainGeneration()),new Subject(10,17,1,subject.chainGeneration()),new Subject(10,17,0,UUID.randomUUID())))
            assertThat(intents.requestStop(wrong,Phase.REDACTION,id,0,UUID.randomUUID()).code()).isEqualTo(Code.STALE);
        assertThat(intents.requestStop(subject,Phase.MINIMUM_REMOVAL,id,0,UUID.randomUUID()).code()).isEqualTo(Code.STALE);
        assertThat(count("commerce_retention_intent_events")).isEqualTo(1);
    }
    @Test void guestIntentRemainsOrderAndGenerationScoped(){
        var guest=jobs.prepare(12,Phase.REDACTION);var guestId=UUID.randomUUID();
        assertThat(intents.prepare(guest,Phase.REDACTION,guestId).code()).isEqualTo(Code.PREPARED);
        assertThat(intents.requestStop(subject,Phase.REDACTION,guestId,0,UUID.randomUUID()).code()).isEqualTo(Code.STALE);
        assertThat(intents.cancelUnstarted(guest,Phase.REDACTION,guestId,0,UUID.randomUUID()).code()).isEqualTo(Code.UPDATED);
    }
    @Test void knownHoldBlocksNewPreparationButDoesNotPreventStoppingAnOldIntent(){
        prepare();new CommerceRetentionHolds(ds,manager).placeOrder(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        assertThat(intents.requestStop(subject,Phase.REDACTION,id,0,UUID.randomUUID()).code()).isEqualTo(Code.UPDATED);
        var next=jobs.prepare(10,Phase.REDACTION);
        assertThat(intents.prepare(next,Phase.REDACTION,UUID.randomUUID()).code()).isEqualTo(Code.BLOCKED);
    }
    @Test void preparationSnapshotAndAssessmentLeaseRemainIndependent(){
        jdbc.update("UPDATE commerce_retention_jobs SET attempts=3,next_attempt_at=clock_timestamp()-interval '1 second'");
        var work=jobs.claim(subject,Phase.REDACTION).orElseThrow();
        var before=jdbc.queryForMap("SELECT proof_revision,attempts,run_token,run_until,state FROM commerce_retention_jobs WHERE generation=?",subject.chainGeneration());
        prepare();
        assertThat(jdbc.queryForMap("SELECT proof_revision,attempts,run_token,run_until,state FROM commerce_retention_jobs WHERE generation=?",subject.chainGeneration())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT prepared_proof_revision FROM commerce_retention_intents",Long.class)).isEqualTo(work.proofRevision());
    }
    @Test void databaseRejectsAuthorizationRewritingHistoryAndInvalidTransitions(){
        prepare();
        for(String sql:List.of("UPDATE commerce_retention_intents SET state='AUTHORIZED',revision=1,last_request_id='"+UUID.randomUUID()+"'",
            "UPDATE commerce_retention_intents SET state='COMPLETED',revision=1,last_request_id='"+UUID.randomUUID()+"'",
            "UPDATE commerce_retention_intents SET prepared_proof_revision=1",
            "DELETE FROM commerce_retention_intents","DELETE FROM commerce_retention_intent_events","UPDATE commerce_retention_intent_events SET state='CANCELLED'"))
            assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("commerce_retention_intent_events")).isEqualTo(1);
    }
    @Test void quarantineIsMonotonicAndKeepsEveryPreviousEvent(){
        prepare();intents.requestStop(subject,Phase.REDACTION,id,0,UUID.randomUUID());intents.cancelUnstarted(subject,Phase.REDACTION,id,1,UUID.randomUUID());
        jdbc.update("UPDATE commerce_retention_intents SET state='QUARANTINED',restore_blocked=TRUE,revision=revision+1,last_request_id=?",UUID.randomUUID());
        assertThat(prepare().code()).isEqualTo(Code.QUARANTINED);
        assertThat(intents.requestStop(subject,Phase.REDACTION,id,3,UUID.randomUUID()).code()).isEqualTo(Code.QUARANTINED);
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_intents SET restore_blocked=FALSE,state='WAITING_AUTHORIZATION',revision=revision+1,last_request_id=?",UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("commerce_retention_intent_events")).isEqualTo(4);
    }
    @Test void eventInsertFailureRollsBackTheHeaderTransition(){
        prepare();jdbc.execute("CREATE FUNCTION fixture_intent_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_intent_failure BEFORE INSERT ON commerce_retention_intent_events FOR EACH ROW EXECUTE FUNCTION fixture_intent_failure()");
        try{assertThat(intents.requestStop(subject,Phase.REDACTION,id,0,UUID.randomUUID()).code()).isEqualTo(Code.UNAVAILABLE);}
        finally{jdbc.execute("DROP TRIGGER fixture_intent_failure ON commerce_retention_intent_events");jdbc.execute("DROP FUNCTION fixture_intent_failure()");}
        assertThat(jdbc.queryForObject("SELECT revision FROM commerce_retention_intents",Long.class)).isZero();assertThat(count("commerce_retention_intent_events")).isEqualTo(1);
    }
    @Test void concurrentRequestsShareOnePreparationAndOneStopEvent()throws Exception{
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(this::prepare);var b=pool.submit(this::prepare);
            assertThat(List.of(a.get(10,TimeUnit.SECONDS).code(),b.get(10,TimeUnit.SECONDS).code())).containsExactlyInAnyOrder(Code.PREPARED,Code.IDEMPOTENT);
            var stop=UUID.randomUUID();var x=pool.submit(()->intents.requestStop(subject,Phase.REDACTION,id,0,stop));var y=pool.submit(()->intents.requestStop(subject,Phase.REDACTION,id,0,stop));
            assertThat(List.of(x.get(10,TimeUnit.SECONDS).code(),y.get(10,TimeUnit.SECONDS).code())).containsExactlyInAnyOrder(Code.UPDATED,Code.IDEMPOTENT);
            assertThat(count("commerce_retention_intent_events")).isEqualTo(2);
        }
    }
    @Test void oneRequestIdCannotBeConsumedByAnotherIntent(){
        prepare();var other=jobs.prepare(11,Phase.REDACTION);var otherId=UUID.randomUUID();intents.prepare(other,Phase.REDACTION,otherId);
        var request=UUID.randomUUID();intents.requestStop(subject,Phase.REDACTION,id,0,request);
        assertThat(intents.requestStop(other,Phase.REDACTION,otherId,0,request).code()).isEqualTo(Code.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT revision FROM commerce_retention_intents WHERE intent_id=?",Long.class,otherId)).isZero();
    }
    @Test void lostCommitAcknowledgementsForPreparationAndStopAreIdempotent()throws Exception{
        var lose=new AtomicBoolean();var uncertain=new DelegatingDataSource(ds){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{
            var delegate=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{try{var value=method.invoke(delegate,args);if(method.getName().equals("commit") && lose.compareAndSet(true,false))throw new java.sql.SQLException("fixture-lost-ack");return value;}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}});
        }};
        var registry=new CommerceRetentionIntents(uncertain,new DataSourceTransactionManager(uncertain));
        lose.set(true);assertThat(registry.prepare(subject,Phase.REDACTION,id).code()).isEqualTo(Code.UNAVAILABLE);
        assertThat(registry.prepare(subject,Phase.REDACTION,id).code()).isEqualTo(Code.IDEMPOTENT);
        var stop=UUID.randomUUID();lose.set(true);assertThat(registry.requestStop(subject,Phase.REDACTION,id,0,stop).code()).isEqualTo(Code.UNAVAILABLE);
        assertThat(registry.requestStop(subject,Phase.REDACTION,id,0,stop).code()).isEqualTo(Code.IDEMPOTENT);
        assertThat(count("commerce_retention_intent_events")).isEqualTo(2);
    }
    @Test void callerTransactionAndUnavailableDatabaseNeverAuthorizeAnything()throws Exception{
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->prepare())).hasMessage("RETENTION_INTENT_CALLER_TRANSACTION");
        var unavailable=org.mockito.Mockito.mock(javax.sql.DataSource.class);org.mockito.Mockito.when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("fixture-only"));
        var result=new CommerceRetentionIntents(unavailable,new DataSourceTransactionManager(unavailable)).prepare(subject,Phase.REDACTION,id);
        assertThat(result.code()).isEqualTo(Code.UNAVAILABLE);assertThat(result.activationAllowed()).isFalse();assertThat(result.executionTerminated()).isFalse();
    }
}
