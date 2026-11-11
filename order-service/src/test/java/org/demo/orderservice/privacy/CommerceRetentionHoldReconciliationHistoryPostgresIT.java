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
import static org.demo.orderservice.privacy.CommerceRetentionHoldReconciliationHistory.*;
import org.demo.orderservice.privacy.CommerceRetentionHoldReconciliationHistory.Code;
import java.time.Instant;

@Testcontainers
class CommerceRetentionHoldReconciliationHistoryPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("hold_probe_history").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionHoldReconciliationHistory history;CommerceRetentionHoldDelivery sender;CommerceRetentionHoldIntake.Registration intake;
    @BeforeAll static void initialize(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_holds,commerce_retention_hold_scopes RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES(10,17,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY','fixture-10',?,'fixture',now(),now())","a".repeat(64));
        intake=new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        history=new CommerceRetentionHoldReconciliationHistory(ds,manager);sender=new CommerceRetentionHoldDelivery(ds,manager);
        for(var p:CommerceRetentionSourceHolds.SOURCES)sender.deliver(10,intake.requestId(),intake.caseReference(),p,c->new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,UUID.randomUUID()));
    }
    Probe observe(Participant source,UUID probe,CommerceRetentionHoldReconciliation.Transport remote){return history.observe(10,intake.requestId(),intake.caseReference(),source,probe,remote);}
    History inspect(Participant source){return history.inspect(10,intake.requestId(),intake.caseReference(),source);}
    SourceHoldInspectionProtocol.Reply reply(SourceHoldInspectionProtocol.Command c){return new SourceHoldInspectionProtocol.Reply(c,SourceHoldInspectionProtocol.Code.MATCH,Instant.now(),2,3);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void durableProbePrecedesRpcAndExactChallengeAndOutcomeArePreserved(Participant source){
        UUID probe=UUID.randomUUID();var result=observe(source,probe,c->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(c.challenge()).isEqualTo(probe);assertThat(count("commerce_retention_hold_probes")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT source_request_id FROM commerce_retention_hold_probes",UUID.class)).isEqualTo(c.delivery().requestId());assertThat(jdbc.queryForObject("SELECT source_hold_id FROM commerce_retention_hold_probes",UUID.class)).isEqualTo(c.expectedHoldId());return reply(c);
        });
        assertThat(result.code()).isEqualTo(Code.RECORDED);assertThat(result.durableHistoryRecorded()).isTrue();assertThat(result.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);assertThat(result.activationAllowed()).isFalse();assertThat(result.completeCaseCoverage()).isFalse();assertThat(result.repairPerformed()).isFalse();
        var h=inspect(source);assertThat(h.entries()).hasSize(1);assertThat(h.entries().getFirst().outcome()).isEqualTo(result.outcome());assertThat(h.historyOnly()).isTrue();assertThat(h.activationAllowed()).isFalse();assertThatThrownBy(()->h.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(count("commerce_retention_hold_delivery_attempts")).isEqualTo(7);assertThat(count("commerce_retention_hold_delivery_receipts")).isEqualTo(7);
    }
    @ParameterizedTest @EnumSource(SourceHoldInspectionProtocol.Code.class)
    void diagnosticCodesAreRecordedAsHistoryWithoutGrantingAuthority(SourceHoldInspectionProtocol.Code code){
        var result=observe(Participant.PAYMENT,UUID.randomUUID(),c->new SourceHoldInspectionProtocol.Reply(c,code,Instant.now(),code==SourceHoldInspectionProtocol.Code.UNKNOWN_PURCHASE?0:2,code==SourceHoldInspectionProtocol.Code.UNKNOWN_PURCHASE?0:3));
        assertThat(result.code()).isEqualTo(Code.RECORDED);assertThat(result.activationAllowed()).isFalse();assertThat(inspect(Participant.PAYMENT).entries().getFirst().requiresReview()).isEqualTo(code!=SourceHoldInspectionProtocol.Code.MATCH);
    }
    @Test void repeatedProbeReadsSamePersistedOutcomeWithoutRpc(){
        UUID probe=UUID.randomUUID();var first=observe(Participant.PAYMENT,probe,this::reply);var again=observe(Participant.PAYMENT,probe,c->{throw new AssertionError("No repeat RPC");});assertThat(again).isEqualTo(first);assertThat(count("commerce_retention_hold_probes")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"order","request","case"})
    void mismatchedScopeExposesNoHistoryAndCannotStartProbe(String change){
        observe(Participant.PAYMENT,UUID.randomUUID(),this::reply);long order=change.equals("order")?11:10;UUID request=change.equals("request")?UUID.randomUUID():intake.requestId(),ref=change.equals("case")?UUID.randomUUID():intake.caseReference();
        assertThat(history.inspect(order,request,ref,Participant.PAYMENT).entries()).isEmpty();assertThat(history.observe(order,request,ref,Participant.PAYMENT,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(Code.UNKNOWN_REQUEST);assertThat(count("commerce_retention_hold_probes")).isEqualTo(1);
    }
    @Test void callerTransactionsAndInvalidInputAreRejected(){
        new TransactionTemplate(manager).executeWithoutResult(tx->{assertThatThrownBy(()->inspect(Participant.PAYMENT)).hasMessage("RETENTION_HOLD_HISTORY_CALLER_TRANSACTION");assertThatThrownBy(()->observe(Participant.PAYMENT,UUID.randomUUID(),this::reply)).hasMessage("RETENTION_HOLD_HISTORY_CALLER_TRANSACTION");});
        assertThatThrownBy(()->observe(Participant.PAYMENT,null,this::reply)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->history.inspect(0,intake.requestId(),intake.caseReference(),Participant.PAYMENT)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->inspect(Participant.ORDER)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void probeBudgetIsIndependentBoundedAndNeverReset(Participant source){
        for(int n=1;n<=8;n++)assertThat(observe(source,UUID.randomUUID(),c->{throw new IllegalStateException("fixture unavailable");}).code()).isEqualTo(Code.RECORDED);
        assertThat(observe(source,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(Code.LIMIT_REACHED);var h=inspect(source);assertThat(h.entries()).hasSize(8);assertThat(h.entries()).allSatisfy(e->{assertThat(e.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNAVAILABLE);assertThat(e.requiresReview()).isTrue();});assertThat(count("commerce_retention_hold_delivery_attempts")).isEqualTo(7);
    }
    @Test void historyIsFilteredBySourceAndDoesNotUseNetworkOrCreateRows(){
        observe(Participant.PAYMENT,UUID.randomUUID(),this::reply);observe(Participant.RETURNS,UUID.randomUUID(),this::reply);
        assertThat(inspect(Participant.PAYMENT).entries()).hasSize(1);assertThat(inspect(Participant.INVENTORY).entries()).isEmpty();assertThat(count("commerce_retention_hold_probes")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"parent","order-scope","customer-scope","order-state"})
    void quarantinePreservesHistoricalResultsAndBlocksNewProbesInApiAndSql(String scope){
        var p=observe(Participant.PAYMENT,UUID.randomUUID(),this::reply);
        switch(scope){case "parent"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "order-scope"->jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");case "customer-scope"->jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id,restore_blocked) VALUES('CUSTOMER',17,TRUE)");case "order-state"->jdbc.update("INSERT INTO commerce_retention_order_state(order_id,restore_blocked) VALUES(10,TRUE)");}
        var h=inspect(Participant.PAYMENT);assertThat(h.code()).isEqualTo(Code.QUARANTINED);assertThat(h.entries().getFirst().outcome()).isEqualTo(p.outcome());assertThat(observe(Participant.PAYMENT,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(Code.QUARANTINED);
        assertThatThrownBy(()->insertProbe(Participant.PAYMENT,UUID.randomUUID(),2)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    void insertProbe(Participant source,UUID probe,int number){jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) SELECT request_id,source,?,?,source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source=?",number,probe,intake.requestId(),source.name());}
    @Test void immutableSqlHistoryRejectsBudgetResetAndBorrowedReceiptIdentity(){
        observe(Participant.PAYMENT,UUID.randomUUID(),this::reply);
        for(String sql:List.of("DELETE FROM commerce_retention_hold_probes","UPDATE commerce_retention_hold_probes SET probe_no=1","DELETE FROM commerce_retention_hold_probe_results","UPDATE commerce_retention_hold_probe_results SET code='UNAVAILABLE'"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->insertProbe(Participant.PAYMENT,UUID.randomUUID(),3)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) VALUES(?,'PAYMENT',2,?,?,?)",intake.requestId(),UUID.randomUUID(),intake.sourceRequests().get(Participant.RETURNS),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void resultShapesAndOrphanOutcomesAreRejectedBySql(){
        UUID probe=UUID.randomUUID();insertProbe(Participant.PAYMENT,probe,1);
        for(String values:List.of("'MATCH_AS_OF_READ',NULL,2,3","'UNAVAILABLE',clock_timestamp(),0,0","'SOURCE_UNKNOWN',clock_timestamp(),2,3"))assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES('"+probe+"',"+values+")")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,binding_epoch,source_revision) VALUES(?,'UNAVAILABLE',0,0)",UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void resultRollbackLeavesAnUncertainProbeThatRetryNeverRedispatches(){
        jdbc.execute("CREATE FUNCTION fixture_probe_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only'; END; $$");jdbc.execute("CREATE TRIGGER fixture_probe_failure BEFORE INSERT ON commerce_retention_hold_probe_results FOR EACH ROW EXECUTE FUNCTION fixture_probe_failure()");UUID probe=UUID.randomUUID();
        try{assertThat(observe(Participant.PAYMENT,probe,this::reply).code()).isEqualTo(Code.UNAVAILABLE);}finally{jdbc.execute("DROP TRIGGER fixture_probe_failure ON commerce_retention_hold_probe_results");jdbc.execute("DROP FUNCTION fixture_probe_failure()");}
        assertThat(observe(Participant.PAYMENT,probe,c->{throw new AssertionError();}).code()).isEqualTo(Code.PENDING);assertThat(inspect(Participant.PAYMENT).entries().getFirst().outcomeUnknown()).isTrue();
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
    @Test void unknownProbeCommitDoesNotSendRpcAndRetryOnlyReadsPending(){
        var uncertain=loseCommitAfter("INSERT INTO commerce_retention_hold_probes(");var wrapper=new CommerceRetentionHoldReconciliationHistory(uncertain,new DataSourceTransactionManager(uncertain));UUID probe=UUID.randomUUID();
        assertThat(wrapper.observe(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,probe,c->{throw new AssertionError();}).code()).isEqualTo(Code.UNAVAILABLE);assertThat(observe(Participant.PAYMENT,probe,c->{throw new AssertionError();}).code()).isEqualTo(Code.PENDING);
    }
    @Test void unknownResultCommitCanBeReadIdempotentlyWithoutNewRpc(){
        var uncertain=loseCommitAfter("INSERT INTO commerce_retention_hold_probe_results(");var wrapper=new CommerceRetentionHoldReconciliationHistory(uncertain,new DataSourceTransactionManager(uncertain));UUID probe=UUID.randomUUID();
        assertThat(wrapper.observe(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,probe,this::reply).code()).isEqualTo(Code.UNAVAILABLE);assertThat(observe(Participant.PAYMENT,probe,c->{throw new AssertionError();}).code()).isEqualTo(Code.RECORDED);assertThat(count("commerce_retention_hold_probes")).isEqualTo(1);
    }
    @Test void concurrentSameProbeSendsOneRpcAndReadersSeePendingWhileItRuns()throws Exception{
        UUID probe=UUID.randomUUID();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->observe(Participant.PAYMENT,probe,c->{calls.incrementAndGet();arrived.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return reply(c);}));
            try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();assertThat(observe(Participant.PAYMENT,probe,c->{throw new AssertionError();}).code()).isEqualTo(Code.PENDING);assertThat(inspect(Participant.PAYMENT).entries().getFirst().outcomeUnknown()).isTrue();}finally{release.countDown();}
            assertThat(future.get(5,TimeUnit.SECONDS).code()).isEqualTo(Code.RECORDED);assertThat(calls).hasValue(1);
        }
    }
    @Test void quarantineWhileRpcRunsStoresOnlyNegativeRootQuarantine()throws Exception{
        UUID probe=UUID.randomUUID();var result=observe(Participant.PAYMENT,probe,c->{jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");return reply(c);});
        assertThat(result.code()).isEqualTo(Code.RECORDED);assertThat(result.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED);assertThat(result.outcome().observedAt()).isNull();assertThat(inspect(Participant.PAYMENT).code()).isEqualTo(Code.QUARANTINED);
    }
    @Test void pendingProbeFromDeadWorkerCannotBeResentAndProbeIdCannotMoveSource(){
        UUID probe=UUID.randomUUID();insertProbe(Participant.PAYMENT,probe,1);assertThat(observe(Participant.PAYMENT,probe,c->{throw new AssertionError();}).code()).isEqualTo(Code.PENDING);assertThat(observe(Participant.RETURNS,probe,c->{throw new AssertionError();}).code()).isEqualTo(Code.CONFLICT);assertThat(inspect(Participant.PAYMENT).entries().getFirst().requiresReview()).isTrue();
    }
    @Test void requestWithoutHistoricalSourceReceiptCannotCreateProbe(){
        var other=new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        assertThat(history.observe(10,other.requestId(),other.caseReference(),Participant.PAYMENT,UUID.randomUUID(),c->{throw new AssertionError();}).code()).isEqualTo(Code.NO_HISTORICAL_RECEIPT);assertThat(count("commerce_retention_hold_probes")).isZero();
    }
    @Test void pendingResultAfterQuarantineCannotBeInsertedAsMatch(){
        UUID probe=UUID.randomUUID();insertProbe(Participant.PAYMENT,probe,1);jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");
        assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,'MATCH_AS_OF_READ',clock_timestamp(),2,3)",probe)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,binding_epoch,source_revision) VALUES(?,'ROOT_QUARANTINED',0,0)",probe);assertThat(inspect(Participant.PAYMENT).entries().getFirst().outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED);
    }
}
