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
import static org.demo.orderservice.privacy.CommerceRetentionHoldRecoveryPlanning.*;
import org.demo.orderservice.privacy.CommerceRetentionHoldRecoveryPlanning.Code;
import org.demo.orderservice.privacy.CommerceRetentionHoldRecoveryPlanning.Result;
import org.demo.orderservice.privacy.CommerceRetentionHoldRepairAssessment.State;
import java.time.Instant;

@Testcontainers
class CommerceRetentionHoldRecoveryPlanningPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("hold_recovery_planning").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionHoldRecoveryPlanning planning;CommerceRetentionHoldRepairAssessment assessment;CommerceRetentionHoldReconciliationHistory history;CommerceRetentionHoldDelivery sender;CommerceRetentionHoldIntake.Registration intake;
    @BeforeAll static void initialize(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_holds,commerce_retention_hold_scopes RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES(10,17,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY','fixture-10',?,'fixture',now(),now())","a".repeat(64));
        intake=new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        planning=new CommerceRetentionHoldRecoveryPlanning(ds,manager);assessment=new CommerceRetentionHoldRepairAssessment(ds,manager);history=new CommerceRetentionHoldReconciliationHistory(ds,manager);sender=new CommerceRetentionHoldDelivery(ds,manager);
        for(var p:CommerceRetentionSourceHolds.SOURCES)sender.deliver(10,intake.requestId(),intake.caseReference(),p,c->new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,UUID.randomUUID()));
    }
    UUID put(Participant p,CommerceRetentionHoldReconciliation.Code code,Instant time){
        int n=jdbc.queryForObject("SELECT count(*)+1 FROM commerce_retention_hold_probes WHERE request_id=? AND source=?",Integer.class,intake.requestId(),p.name());UUID probe=UUID.randomUUID();
        jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) SELECT request_id,source,?,?,source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source=?",n,probe,intake.requestId(),p.name());
        if(code!=null){boolean observed=Set.of(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,CommerceRetentionHoldReconciliation.Code.SOURCE_CONFLICT,CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN,CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED).contains(code);boolean versioned=observed && code!=CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN;
            jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,?,?,?,?)",probe,code.name(),observed?java.sql.Timestamp.from(time):null,versioned?2:0,versioned?3:0);
        }return probe;
    }
    javax.sql.DataSource loseCommitAfter(String statement){
        var lose=new java.util.concurrent.atomic.AtomicBoolean(true);
        return new org.springframework.jdbc.datasource.AbstractDataSource(){public java.sql.Connection getConnection()throws java.sql.SQLException{
            var connection=ds.getConnection();boolean[] target={false};return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.contains(statement))target[0]=true;
                Object result;try{result=method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                if(method.getName().equals("commit") && target[0] && lose.compareAndSet(true,false))throw new java.sql.SQLException("fixture commit acknowledgement lost","08006");return result;
            });
        }public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{return getConnection();}};
    }
    Result resolve(Participant source,UUID target,UUID replacement,UUID id){return planning.supersede(10,intake.requestId(),intake.caseReference(),source,target,replacement,id);}
    Result prepare(Participant source,UUID id){return planning.prepare(10,intake.requestId(),intake.caseReference(),source,id);}
    History inspect(Participant source){return planning.inspect(10,intake.requestId(),intake.caseReference(),source);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void noAuthority(Result r){assertThat(r.executionTerminated()).isFalse();assertThat(r.repairAllowed()).isFalse();assertThat(r.activationAllowed()).isFalse();assertThat(r.completeCaseCoverage()).isFalse();}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void supersessionAndIntentKeepExactIdentityWithoutResettingHistoryOrGrantingAuthority(Participant source){
        UUID old=put(source,null,null),replacement=put(source,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now()),resolution=UUID.randomUUID(),intent=UUID.randomUUID();
        var r=resolve(source,old,replacement,resolution);assertThat(r.code()).isEqualTo(Code.RECORDED);noAuthority(r);assertThat(resolve(source,old,replacement,resolution).code()).isEqualTo(Code.IDEMPOTENT);
        var p=prepare(source,intent);assertThat(p.code()).isEqualTo(Code.PREPARED);assertThat(p.basisProbeId()).isEqualTo(replacement);noAuthority(p);assertThat(prepare(source,intent).code()).isEqualTo(Code.IDEMPOTENT);
        var h=inspect(source);assertThat(h.resolutions()).hasSize(1);assertThat(h.intents()).hasSize(1);assertThat(h.intents().getFirst().sourceRequestId()).isEqualTo(intake.sourceRequests().get(source));assertThat(h.intents().getFirst().expectedHoldId()).isEqualTo(jdbc.queryForObject("SELECT source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE source=?",UUID.class,source.name()));assertThatThrownBy(()->h.intents().clear()).isInstanceOf(UnsupportedOperationException.class);assertThat(h.repairAllowed()).isFalse();
        var a=assessment.assess(10,intake.requestId(),intake.caseReference());var s=a.sources().stream().filter(v->v.source()==source).findFirst().orElseThrow();assertThat(s.state()).isEqualTo(State.MISSING_HOLD_CANDIDATE);assertThat(s.pendingProbes()).isZero();assertThat(s.resolvedPendingProbes()).isEqualTo(1);assertThat(s.probesUsed()).isEqualTo(2);assertThat(s.remainingProbes()).isEqualTo(6);assertThat(s.candidate().executionAllowed()).isFalse();
        var oldEntry=history.inspect(10,intake.requestId(),intake.caseReference(),source).entries().getFirst();assertThat(oldEntry.supersededBy()).isEqualTo(replacement);assertThat(oldEntry.outcome()).isNull();assertThat(oldEntry.outcomeUnknown()).isTrue();assertThat(oldEntry.unresolvedPending()).isFalse();assertThat(oldEntry.requiresReview()).isTrue();assertThat(count("commerce_retention_hold_probe_results")).isEqualTo(1);assertThat(count("commerce_retention_hold_delivery_attempts")).isEqualTo(7);
    }
    @Test void laterMatchCanAccountReadOnlyPendingWithoutPreparingRepair(){
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());assertThat(resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID()).code()).isEqualTo(Code.RECORDED);assertThat(prepare(Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.NOT_CANDIDATE);
    }
    @ParameterizedTest @ValueSource(strings={"other-source","older","not-latest","pending","conflict","unknown","quarantine","unavailable","expired","future"})
    void replacementMustBeNewerLatestExactScopedFreshCompletedRead(String variant){
        UUID older=variant.equals("older")?put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now()):null;
        UUID old=put(Participant.PAYMENT,null,null);var source=variant.equals("other-source")?Participant.RETURNS:Participant.PAYMENT;
        var code=switch(variant){case "pending"->null;case "conflict"->CommerceRetentionHoldReconciliation.Code.SOURCE_CONFLICT;case "unknown"->CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN;case "quarantine"->CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED;case "unavailable"->CommerceRetentionHoldReconciliation.Code.UNAVAILABLE;default->CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ;};
        UUID replacement=older!=null?older:put(source,code,Instant.now().plusSeconds(variant.equals("expired")?-60:variant.equals("future")?60:0));
        if(variant.equals("not-latest"))put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());
        assertThat(resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID()).code()).isEqualTo(Code.INVALID_REPLACEMENT);assertThat(count("commerce_retention_hold_probe_resolutions")).isZero();
    }
    @Test void completedTargetCannotBecomeSuperseded(){
        UUID old=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now()),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());assertThat(resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID()).code()).isEqualTo(Code.NOT_PENDING);
    }
    @Test void unknownTargetCannotProduceResolution(){
        UUID replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());assertThat(resolve(Participant.PAYMENT,UUID.randomUUID(),replacement,UUID.randomUUID()).code()).isEqualTo(Code.UNKNOWN_PROBE);
    }
    @ParameterizedTest @ValueSource(strings={"order","request","case"})
    void exactScopeIsRequiredForWritesAndHistory(String mismatch){
        long order=mismatch.equals("order")?11:10;UUID request=mismatch.equals("request")?UUID.randomUUID():intake.requestId(),ref=mismatch.equals("case")?UUID.randomUUID():intake.caseReference();
        assertThat(planning.prepare(order,request,ref,Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.UNKNOWN_REQUEST);assertThat(planning.supersede(order,request,ref,Participant.PAYMENT,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()).code()).isEqualTo(Code.UNKNOWN_REQUEST);assertThat(planning.inspect(order,request,ref,Participant.PAYMENT).intents()).isEmpty();
    }
    @Test void callerTransactionsAndInvalidInputsAreRejected(){
        new TransactionTemplate(manager).executeWithoutResult(tx->{assertThatThrownBy(()->prepare(Participant.PAYMENT,UUID.randomUUID())).hasMessage("RETENTION_HOLD_RECOVERY_CALLER_TRANSACTION");assertThatThrownBy(()->inspect(Participant.PAYMENT)).hasMessage("RETENTION_HOLD_RECOVERY_CALLER_TRANSACTION");});assertThatThrownBy(()->prepare(Participant.PAYMENT,null)).isInstanceOf(IllegalArgumentException.class);UUID id=UUID.randomUUID();assertThatThrownBy(()->resolve(Participant.PAYMENT,id,id,id)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->inspect(Participant.ORDER)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"parent","order-scope","customer-scope","order-state"})
    void quarantineBlocksPlanningAndPreservesHistoricalRows(String scope){
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID());prepare(Participant.PAYMENT,UUID.randomUUID());
        switch(scope){case "parent"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "order-scope"->jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");case "customer-scope"->jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id,restore_blocked) VALUES('CUSTOMER',17,TRUE)");case "order-state"->jdbc.update("INSERT INTO commerce_retention_order_state(order_id,restore_blocked) VALUES(10,TRUE)");}
        assertThat(prepare(Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.QUARANTINED);assertThat(resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID()).code()).isEqualTo(Code.QUARANTINED);var h=inspect(Participant.PAYMENT);assertThat(h.code()).isEqualTo(Code.QUARANTINED);assertThat(h.resolutions()).hasSize(1);assertThat(h.intents()).hasSize(1);
        assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_repair_intents(intent_id,request_id,source,basis_probe_id,source_request_id,source_hold_id) SELECT ?,request_id,source,probe_id,source_request_id,source_hold_id FROM commerce_retention_hold_probes WHERE probe_id=?",UUID.randomUUID(),replacement)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void immutableRowsAndConflictingIdsCannotRewriteResolutionOrIntent(){
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now()),resolution=UUID.randomUUID(),intent=UUID.randomUUID();resolve(Participant.PAYMENT,old,replacement,resolution);prepare(Participant.PAYMENT,intent);
        for(String sql:List.of("DELETE FROM commerce_retention_hold_probe_resolutions","UPDATE commerce_retention_hold_probe_resolutions SET resolution_id=gen_random_uuid()","DELETE FROM commerce_retention_hold_repair_intents","UPDATE commerce_retention_hold_repair_intents SET intent_id=gen_random_uuid()"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID()).code()).isEqualTo(Code.CONFLICT);assertThat(prepare(Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.CONFLICT);assertThat(prepare(Participant.RETURNS,intent).code()).isEqualTo(Code.CONFLICT);
    }
    @Test void supersededProbeCannotRedispatchOrAppendLateResultEvenThroughOldSqlPath(){
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID());assertThat(history.observe(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,old,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.SUPERSEDED);
        assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,'MATCH_AS_OF_READ',clock_timestamp(),2,3)",old)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("commerce_retention_hold_probes")).isEqualTo(2);assertThat(count("commerce_retention_hold_probe_results")).isEqualTo(1);
    }
    @Test void stillRunningReadOnlyWorkerCannotAppendItsLateOutcomeAfterSupersession()throws Exception{
        UUID old=UUID.randomUUID();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->history.observe(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,old,c->{arrived.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return new SourceHoldInspectionProtocol.Reply(c,SourceHoldInspectionProtocol.Code.MATCH,Instant.now(),2,3);}));
            try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();UUID replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());var resolution=resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID());assertThat(resolution.code()).isEqualTo(Code.RECORDED);assertThat(resolution.executionTerminated()).isFalse();}finally{release.countDown();}
            assertThat(future.get(5,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.SUPERSEDED);assertThat(count("commerce_retention_hold_probe_results")).isEqualTo(1);
        }
    }
    @Test void resultThatCommittedFirstPreventsSupersession(){
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,'MATCH_AS_OF_READ',clock_timestamp(),2,3)",old);assertThat(resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID()).code()).isEqualTo(Code.NOT_PENDING);assertThat(count("commerce_retention_hold_probe_resolutions")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"no-probe","pending","match","conflict","unknown","source-q","unavailable","expired","future","older-missing-later-match"})
    void intentRequiresLatestFreshMissingAndNoUnresolvedPending(String value){
        if(!value.equals("no-probe")){
            var code=switch(value){case "pending"->null;case "match"->CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ;case "conflict"->CommerceRetentionHoldReconciliation.Code.SOURCE_CONFLICT;case "unknown"->CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN;case "source-q"->CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED;case "unavailable"->CommerceRetentionHoldReconciliation.Code.UNAVAILABLE;default->CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE;};
            put(Participant.PAYMENT,code,Instant.now().plusSeconds(value.equals("expired")?-60:value.equals("future")?60:0));
            if(value.equals("older-missing-later-match"))put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());
        }
        assertThat(prepare(Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.NOT_CANDIDATE);assertThat(count("commerce_retention_hold_repair_intents")).isZero();
    }
    @Test void unresolvedPendingBlocksIntentUntilItIsExplicitlyAccounted(){
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());assertThat(prepare(Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.NOT_CANDIDATE);resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID());assertThat(prepare(Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.PREPARED);assertThat(count("commerce_retention_hold_probes")).isEqualTo(2);
    }
    @Test void existingIntentCannotRebindToNewerProbe(){
        UUID first=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now()),intent=UUID.randomUUID();prepare(Participant.PAYMENT,intent);UUID second=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());assertThat(prepare(Participant.PAYMENT,intent).basisProbeId()).isEqualTo(first);var next=prepare(Participant.PAYMENT,UUID.randomUUID());assertThat(next.code()).isEqualTo(Code.PREPARED);assertThat(next.basisProbeId()).isEqualTo(second);
    }
    @Test void oneIntentPerBasisBoundsHistoryWithoutFreeingProbeBudget(){
        for(int n=0;n<8;n++){put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());assertThat(prepare(Participant.PAYMENT,UUID.randomUUID()).code()).isEqualTo(Code.PREPARED);}
        assertThat(inspect(Participant.PAYMENT).intents()).hasSize(8);assertThat(count("commerce_retention_hold_probes")).isEqualTo(8);assertThat(history.observe(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.LIMIT_REACHED);
    }
    @ParameterizedTest @ValueSource(strings={"resolution","intent"})
    void lostCommitAcknowledgementCanBeRetriedWithoutDuplicatePlanning(String kind){
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now()),id=UUID.randomUUID();
        if(kind.equals("intent"))resolve(Participant.PAYMENT,old,replacement,UUID.randomUUID());
        var uncertain=loseCommitAfter(kind.equals("resolution")?"INSERT INTO commerce_retention_hold_probe_resolutions(":"INSERT INTO commerce_retention_hold_repair_intents(");var adapter=new CommerceRetentionHoldRecoveryPlanning(uncertain,new DataSourceTransactionManager(uncertain));
        var result=kind.equals("resolution")?adapter.supersede(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,old,replacement,id):adapter.prepare(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,id);assertThat(result.code()).isEqualTo(Code.UNAVAILABLE);
        assertThat((kind.equals("resolution")?resolve(Participant.PAYMENT,old,replacement,id):prepare(Participant.PAYMENT,id)).code()).isEqualTo(Code.IDEMPOTENT);assertThat(count(kind.equals("resolution")?"commerce_retention_hold_probe_resolutions":"commerce_retention_hold_repair_intents")).isEqualTo(1);
    }
    @Test void concurrentSameResolutionAndIntentHaveOneDurableIdentity()throws Exception{
        UUID old=put(Participant.PAYMENT,null,null),replacement=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now()),resolution=UUID.randomUUID(),intent=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->resolve(Participant.PAYMENT,old,replacement,resolution));var b=pool.submit(()->resolve(Participant.PAYMENT,old,replacement,resolution));assertThat(List.of(a.get(5,TimeUnit.SECONDS).code(),b.get(5,TimeUnit.SECONDS).code())).containsExactlyInAnyOrder(Code.RECORDED,Code.IDEMPOTENT);
            var c=pool.submit(()->prepare(Participant.PAYMENT,intent));var d=pool.submit(()->prepare(Participant.PAYMENT,intent));assertThat(List.of(c.get(5,TimeUnit.SECONDS).code(),d.get(5,TimeUnit.SECONDS).code())).containsExactlyInAnyOrder(Code.PREPARED,Code.IDEMPOTENT);
        }assertThat(count("commerce_retention_hold_probe_resolutions")).isEqualTo(1);assertThat(count("commerce_retention_hold_repair_intents")).isEqualTo(1);
    }
    @Test void sqlCannotBorrowAnotherSourcesBasisOrReceiptIdentity(){
        UUID basis=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());
        assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_repair_intents(intent_id,request_id,source,basis_probe_id,source_request_id,source_hold_id) SELECT ?,request_id,source,?,source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source='RETURNS'",UUID.randomUUID(),basis,intake.requestId())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("commerce_retention_hold_repair_intents")).isZero();
    }
}
