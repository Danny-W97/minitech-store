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
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.orderservice.privacy.CommerceRetentionHolds.Reason.*;

@Testcontainers
class CommerceRetentionHoldIntakePostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("hold_intake").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionHoldIntake intake;CommerceRetentionHolds holds;
    @BeforeAll static void initialize(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_holds,commerce_retention_hold_scopes RESTART IDENTITY CASCADE");
        for(long id:new long[]{10,11,12}){
            jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES(?,?,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY',?,?,'fixture',now(),now())",id,id==12?null:17L,"fixture-"+id,"a".repeat(64));
        }
        holds=new CommerceRetentionHolds(ds,manager);intake=new CommerceRetentionHoldIntake(ds,manager);
    }
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    CommerceRetentionHoldIntake.Registration register(long order,UUID request,UUID caseRef){return intake.register(order,request,caseRef,Guard.NO_PRESERVATION_HOLD,PRESERVATION_REQUEST);}
    @ParameterizedTest @EnumSource(value=Guard.class,names={"NO_PRESERVATION_HOLD","NO_OPEN_OR_REOPENED_CASE","NO_ACTIVE_RETRY_OR_DLQ"})
    void registrationAtomicallyLinksNegativeRootHoldAndSevenPlannedSources(Guard guard){
        var job=new CommerceRetentionJobs(ds,manager).prepare(10,Phase.REDACTION);
        var r=intake.register(10,UUID.randomUUID(),UUID.randomUUID(),guard,DEPENDENCY_REVIEW);
        assertThat(r.code()).isEqualTo(CommerceRetentionHoldIntake.Code.REGISTERED);assertThat(r.sourceRequests().keySet()).isEqualTo(CommerceRetentionSourceHolds.SOURCES);
        assertThat(new HashSet<>(r.sourceRequests().values())).hasSize(7);assertThat(r.remoteDeliveryConfirmed()).isFalse();assertThat(r.activationAllowed()).isFalse();
        assertThat(count("commerce_retention_hold_requests")).isEqualTo(1);assertThat(count("commerce_retention_hold_source_requests")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,job.chainGeneration())).isFalse();
        assertThat(jdbc.queryForObject("SELECT guard FROM commerce_retention_holds WHERE hold_id=?",String.class,r.rootHoldId())).isEqualTo(guard.name());
        assertThatThrownBy(()->r.sourceRequests().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void retryKeepsCaseRootHoldAndAllSourceIdentifiers(){
        var request=UUID.randomUUID();var ref=UUID.randomUUID();var first=register(10,request,ref);var again=register(10,request,ref);
        assertThat(again.code()).isEqualTo(CommerceRetentionHoldIntake.Code.IDEMPOTENT);assertThat(again.rootHoldId()).isEqualTo(first.rootHoldId());assertThat(again.sourceRequests()).isEqualTo(first.sourceRequests());
        assertThat(count("commerce_retention_holds")).isEqualTo(1);assertThat(count("commerce_retention_hold_source_requests")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT revision FROM commerce_retention_hold_scopes WHERE scope_type='ORDER' AND subject_id=10",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"order","case","guard","reason"})
    void conflictingRequestCannotRebindOrLeaveAnAdditionalBlock(String change){
        var request=UUID.randomUUID();var ref=UUID.randomUUID();register(10,request,ref);var secondJob=new CommerceRetentionJobs(ds,manager).prepare(11,Phase.REDACTION);
        assertThatThrownBy(()->intake.register(change.equals("order")?11:10,request,change.equals("case")?UUID.randomUUID():ref,change.equals("guard")?Guard.NO_OPEN_OR_REOPENED_CASE:Guard.NO_PRESERVATION_HOLD,change.equals("reason")?OPEN_CASE:PRESERVATION_REQUEST)).hasMessage("RETENTION_HOLD_INTAKE_CONFLICT");
        assertThat(count("commerce_retention_holds")).isEqualTo(1);assertThat(count("commerce_retention_hold_requests")).isEqualTo(1);assertThat(count("commerce_retention_hold_source_requests")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,secondJob.chainGeneration())).isTrue();
    }
    @Test void invalidOrUnknownInputCreatesNoMetadata(){
        for(Runnable action:List.<Runnable>of(()->register(999,UUID.randomUUID(),UUID.randomUUID()),()->register(0,UUID.randomUUID(),UUID.randomUUID()),()->register(10,null,UUID.randomUUID()),()->register(10,UUID.randomUUID(),null),()->intake.register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.INDEPENDENT_JOURNAL_CURRENT,PRESERVATION_REQUEST)))assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("commerce_retention_holds")).isZero();assertThat(count("commerce_retention_hold_requests")).isZero();
    }
    @Test void sourcePlanFailureRollsBackRootHoldIntakeAndJobInvalidation(){
        var job=new CommerceRetentionJobs(ds,manager).prepare(10,Phase.REDACTION);
        jdbc.execute("CREATE FUNCTION fixture_intake_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_intake_failure BEFORE INSERT ON commerce_retention_hold_source_requests FOR EACH ROW WHEN (NEW.source='INVENTORY') EXECUTE FUNCTION fixture_intake_failure()");
        try{assertThatThrownBy(()->register(10,UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("DROP TRIGGER fixture_intake_failure ON commerce_retention_hold_source_requests");jdbc.execute("DROP FUNCTION fixture_intake_failure()");}
        assertThat(count("commerce_retention_holds")).isZero();assertThat(count("commerce_retention_hold_requests")).isZero();assertThat(count("commerce_retention_hold_source_requests")).isZero();
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,job.chainGeneration())).isTrue();
        assertThat(jdbc.queryForObject("SELECT revision FROM commerce_retention_hold_scopes WHERE scope_type='ORDER' AND subject_id=10",Long.class)).isZero();
    }
    @Test void databaseRejectsCommitOfAnIncompleteSourcePlan(){
        var hold=holds.placeOrder(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,PRESERVATION_REQUEST);var request=UUID.randomUUID();
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            jdbc.update("INSERT INTO commerce_retention_hold_requests(request_id,order_id,case_reference,guard,reason_code,root_hold_id) VALUES(?,10,?,'NO_PRESERVATION_HOLD','PRESERVATION_REQUEST',?)",request,UUID.randomUUID(),hold);
            for(var p:CommerceRetentionSourceHolds.SOURCES)if(p!=Participant.INVENTORY)jdbc.update("INSERT INTO commerce_retention_hold_source_requests(request_id,source,source_request_id) VALUES(?,?,?)",request,p.name(),UUID.randomUUID());
        })).isInstanceOf(RuntimeException.class);
        assertThat(count("commerce_retention_hold_requests")).isZero();assertThat(count("commerce_retention_hold_source_requests")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"other-order","customer"})
    void requestCannotBorrowAHoldFromAnotherScope(String scope){
        UUID hold=scope.equals("customer")?holds.placeCustomerPurchases(17,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,PRESERVATION_REQUEST):holds.placeOrder(11,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,PRESERVATION_REQUEST);
        assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_requests(request_id,order_id,case_reference,guard,reason_code,root_hold_id) VALUES(?,10,?,'NO_PRESERVATION_HOLD','PRESERVATION_REQUEST',?)",UUID.randomUUID(),UUID.randomUUID(),hold)).isInstanceOf(RuntimeException.class);
        assertThat(count("commerce_retention_hold_requests")).isZero();
    }
    @Test void immutableIdentitiesAndMonotonicQuarantineProtectHistory(){
        var r=register(10,UUID.randomUUID(),UUID.randomUUID());
        for(String sql:List.of("DELETE FROM commerce_retention_hold_requests","UPDATE commerce_retention_hold_requests SET case_reference=gen_random_uuid()","UPDATE commerce_retention_hold_requests SET order_id=11","DELETE FROM commerce_retention_hold_source_requests","UPDATE commerce_retention_hold_source_requests SET source_request_id=gen_random_uuid()"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=FALSE")).isInstanceOf(RuntimeException.class);
        var again=register(10,r.requestId(),r.caseReference());assertThat(again.code()).isEqualTo(CommerceRetentionHoldIntake.Code.QUARANTINED);assertThat(again.sourceRequests()).isEqualTo(r.sourceRequests());assertThat(again.remoteDeliveryConfirmed()).isFalse();
    }
    @Test void concurrentIdenticalRequestsHaveOneRootHoldAndOnePlan()throws Exception{
        var request=UUID.randomUUID();var ref=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);Callable<CommerceRetentionHoldIntake.Registration> task=()->{assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();return register(10,request,ref);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertThat(first.sourceRequests()).isEqualTo(second.sourceRequests());assertThat(first.rootHoldId()).isEqualTo(second.rootHoldId());
        }
        assertThat(count("commerce_retention_holds")).isEqualTo(1);assertThat(count("commerce_retention_hold_requests")).isEqualTo(1);assertThat(count("commerce_retention_hold_source_requests")).isEqualTo(7);
    }
    @Test void concurrentCrossOrderRequestConflictLeavesOnlyTheWinningPurchaseBlocked()throws Exception{
        var request=UUID.randomUUID();var ref=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<String>>();
            for(long order:new long[]{10,11})futures.add(pool.submit(()->{assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();try{register(order,request,ref);return "REGISTERED";}catch(IllegalStateException conflict){assertThat(conflict).hasMessage("RETENTION_HOLD_INTAKE_CONFLICT");return "CONFLICT";}}));
            start.countDown();var results=new ArrayList<String>();for(var f:futures)results.add(f.get(10,TimeUnit.SECONDS));assertThat(results).containsExactlyInAnyOrder("REGISTERED","CONFLICT");
        }
        assertThat(count("commerce_retention_holds")).isEqualTo(1);assertThat(count("commerce_retention_hold_requests")).isEqualTo(1);assertThat(count("commerce_retention_hold_source_requests")).isEqualTo(7);
    }
    @Test void callerTransactionIsRejectedBeforeAnyLocalWrite(){
        new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(()->register(10,UUID.randomUUID(),UUID.randomUUID())).hasMessage("RETENTION_HOLD_INTAKE_CALLER_TRANSACTION"));
        assertThat(count("commerce_retention_holds")).isZero();assertThat(count("commerce_retention_hold_requests")).isZero();
    }
    @Test void newRequestCannotEnterARestoredHoldScope(){
        holds.placeOrder(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,PRESERVATION_REQUEST);jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");
        assertThatThrownBy(()->register(10,UUID.randomUUID(),UUID.randomUUID())).hasMessage("RETENTION_HOLD_INTAKE_QUARANTINED");assertThat(count("commerce_retention_hold_requests")).isZero();
    }
    @Test void inspectionRequiresExactOrderRequestAndCaseReference(){
        var r=register(12,UUID.randomUUID(),UUID.randomUUID());assertThat(intake.inspect(12,r.requestId(),r.caseReference())).isPresent();
        assertThat(intake.inspect(11,r.requestId(),r.caseReference())).isEmpty();assertThat(intake.inspect(12,r.requestId(),UUID.randomUUID())).isEmpty();assertThat(intake.inspect(12,UUID.randomUUID(),r.caseReference())).isEmpty();
    }
    @Test void lostCommitAcknowledgementIsNotSuccessAndRetryReusesDurableIdentifiers(){
        var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var request=UUID.randomUUID();var ref=UUID.randomUUID();
        var uncertain=new DelegatingDataSource(ds){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                var c=ds.getConnection();boolean[] inserted={false};return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql && sql.contains("INSERT INTO commerce_retention_hold_requests"))inserted[0]=true;
                    Object result;try{result=method.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("commit") && inserted[0] && lose.compareAndSet(true,false))throw new java.sql.SQLException("fixture commit acknowledgement lost","08006");return result;
                });
            }
        };
        var caller=new CommerceRetentionHoldIntake(uncertain,new DataSourceTransactionManager(uncertain));
        assertThatThrownBy(()->caller.register(10,request,ref,Guard.NO_PRESERVATION_HOLD,PRESERVATION_REQUEST)).isInstanceOf(RuntimeException.class);assertThat(lose.get()).isFalse();
        var stored=intake.inspect(10,request,ref).orElseThrow();var retried=register(10,request,ref);assertThat(retried.sourceRequests()).isEqualTo(stored.sourceRequests());assertThat(retried.code()).isEqualTo(CommerceRetentionHoldIntake.Code.IDEMPOTENT);
        assertThat(count("commerce_retention_holds")).isEqualTo(1);assertThat(count("commerce_retention_hold_source_requests")).isEqualTo(7);assertThat(retried.remoteDeliveryConfirmed()).isFalse();
    }
}
