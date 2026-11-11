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
import static org.demo.orderservice.privacy.CommerceRetentionHoldDeliveryProgress.*;
import org.demo.orderservice.privacy.CommerceRetentionHoldDeliveryProgress.Code;
import org.demo.orderservice.privacy.CommerceRetentionHoldDeliveryProgress.State;

@Testcontainers
class CommerceRetentionHoldDeliveryProgressPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("hold_progress").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionHoldDeliveryProgress progress;CommerceRetentionHoldDelivery sender;CommerceRetentionHoldIntake.Registration intake;
    @BeforeAll static void initialize(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_holds,commerce_retention_hold_scopes RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES(10,17,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY','fixture-10',?,'fixture',now(),now())","a".repeat(64));
        intake=new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        progress=new CommerceRetentionHoldDeliveryProgress(ds,manager);sender=new CommerceRetentionHoldDelivery(ds,manager);
    }
    Progress inspect(){return progress.inspect(10,intake.requestId(),intake.caseReference());}
    Source source(Progress p,Participant who){return p.sources().stream().filter(s->s.source()==who).findFirst().orElseThrow();}
    CommerceRetentionHoldDelivery.Result deliver(Participant p,CommerceRetentionHoldDelivery.Transport remote){return sender.deliver(10,intake.requestId(),intake.caseReference(),p,remote);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void denyAuthority(Progress p){assertThat(p.historyOnly()).isTrue();assertThat(p.currentSourceReconciliationRequired()).isTrue();assertThat(p.activationAllowed()).isFalse();assertThat(p.completeCaseCoverage()).isFalse();}
    @Test void freshPlanHasSevenImmutableCountersAndInspectionChangesNoMetadata(){
        var p=inspect();assertThat(p.code()).isEqualTo(Code.OBSERVED);assertThat(p.sources()).hasSize(7);denyAuthority(p);
        assertThat(p.sources()).allSatisfy(s->{assertThat(s.state()).isEqualTo(State.NOT_ATTEMPTED);assertThat(s.attempts()).isZero();assertThat(s.remainingAttempts()).isEqualTo(8);assertThat(s.sourceRequestId()).isEqualTo(intake.sourceRequests().get(s.source()));assertThat(s.sourceHoldId()).isNull();});
        assertThatThrownBy(()->p.sources().clear()).isInstanceOf(UnsupportedOperationException.class);assertThat(inspect()).isEqualTo(p);
        assertThat(count("commerce_retention_hold_delivery_attempts")).isZero();assertThat(count("commerce_retention_hold_delivery_receipts")).isZero();assertThat(count("commerce_retention_holds")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"order","request","case"})
    void exactScopeIsRequiredBeforeAnyCountersAreDisclosed(String mismatch){
        var p=progress.inspect(mismatch.equals("order")?11:10,mismatch.equals("request")?UUID.randomUUID():intake.requestId(),mismatch.equals("case")?UUID.randomUUID():intake.caseReference());
        assertThat(p.code()).isEqualTo(Code.UNKNOWN_REQUEST);assertThat(p.sources()).isEmpty();denyAuthority(p);
    }
    @Test void invalidInputAndCallerTransactionsAreRejected(){
        for(Runnable r:List.<Runnable>of(()->progress.inspect(0,intake.requestId(),intake.caseReference()),()->progress.inspect(10,null,intake.caseReference()),()->progress.inspect(10,intake.requestId(),null)))assertThatThrownBy(r::run).isInstanceOf(IllegalArgumentException.class);
        new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(this::inspect).hasMessage("RETENTION_HOLD_PROGRESS_CALLER_TRANSACTION"));assertThat(count("commerce_retention_hold_delivery_attempts")).isZero();
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void receiptIsHistoricalAndDoesNotChangeOtherSourcesOrGrantAuthority(Participant who){
        UUID hold=UUID.randomUUID();assertThat(deliver(who,c->new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,hold)).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RECORDED);
        var p=inspect();var s=source(p,who);assertThat(s.state()).isEqualTo(State.HISTORICAL_RECEIPT);assertThat(s.attempts()).isEqualTo(1);assertThat(s.remainingAttempts()).isEqualTo(7);assertThat(s.sourceHoldId()).isEqualTo(hold);denyAuthority(p);
        assertThat(p.sources().stream().filter(v->v.source()!=who)).allSatisfy(v->assertThat(v.state()).isEqualTo(State.NOT_ATTEMPTED));assertThat(inspect()).isEqualTo(p);
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void exhaustedBudgetNeedsReviewAndReadNeverResetsOrRetriesIt(Participant who){
        var calls=new AtomicInteger();for(int i=0;i<8;i++)deliver(who,c->{calls.incrementAndGet();throw new IllegalStateException("fixture uncertain outcome");});
        for(int i=0;i<3;i++){var p=inspect();var s=source(p,who);assertThat(s.state()).isEqualTo(State.REVIEW_REQUIRED);assertThat(s.attempts()).isEqualTo(8);assertThat(s.remainingAttempts()).isZero();assertThat(s.sourceHoldId()).isNull();denyAuthority(p);}
        assertThat(deliver(who,c->{throw new AssertionError("Budget must not be reset");}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.LIMIT_REACHED);assertThat(calls).hasValue(8);assertThat(count("commerce_retention_hold_delivery_attempts")).isEqualTo(8);assertThat(count("commerce_retention_hold_delivery_receipts")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"parent","order-scope","customer-scope","order-state"})
    void quarantinedScopeStillPreservesHistoricalCountersWithoutCurrentAuthority(String scope){
        UUID hold=UUID.randomUUID();deliver(Participant.PAYMENT,c->new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,hold));
        switch(scope){
            case "parent"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");
            case "order-scope"->jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");
            case "customer-scope"->jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id,restore_blocked) VALUES('CUSTOMER',17,TRUE)");
            case "order-state"->jdbc.update("INSERT INTO commerce_retention_order_state(order_id,restore_blocked) VALUES(10,TRUE)");
        }
        var p=inspect();assertThat(p.code()).isEqualTo(Code.QUARANTINED);assertThat(source(p,Participant.PAYMENT).sourceHoldId()).isEqualTo(hold);denyAuthority(p);assertThat(count("commerce_retention_hold_delivery_attempts")).isEqualTo(1);
    }
    @Test void inFlightRpcIsUnknownAndInspectionDoesNotWaitForTransport()throws Exception{
        var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);UUID hold=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)){
            var pending=pool.submit(()->deliver(Participant.INVENTORY,c->{arrived.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,hold);}));
            try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();var p=pool.submit(this::inspect).get(3,TimeUnit.SECONDS);assertThat(source(p,Participant.INVENTORY).state()).isEqualTo(State.OUTCOME_UNKNOWN);assertThat(source(p,Participant.INVENTORY).attempts()).isEqualTo(1);denyAuthority(p);}
            finally{release.countDown();}
            assertThat(pending.get(5,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RECORDED);assertThat(source(inspect(),Participant.INVENTORY).sourceHoldId()).isEqualTo(hold);
        }
    }
    @Test void concurrentReceiptWriterCannotExposeHalfOfItsLocalTransaction()throws Exception{
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);UUID attempt=UUID.randomUUID(),hold=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)){
            var writer=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
                jdbc.queryForList("SELECT request_id FROM commerce_retention_hold_requests WHERE request_id=? FOR UPDATE",intake.requestId());
                jdbc.update("INSERT INTO commerce_retention_hold_delivery_attempts(request_id,source,attempt_no,attempt_id) VALUES(?,'PAYMENT',1,?)",intake.requestId(),attempt);locked.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("INSERT INTO commerce_retention_hold_delivery_receipts(request_id,source,source_request_id,source_hold_id,attempt_id) VALUES(?,'PAYMENT',?,?,?)",intake.requestId(),intake.sourceRequests().get(Participant.PAYMENT),hold,attempt);
            }));
            try{assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var reader=pool.submit(this::inspect);release.countDown();writer.get(5,TimeUnit.SECONDS);var s=source(reader.get(5,TimeUnit.SECONDS),Participant.PAYMENT);assertThat(s.attempts()).isEqualTo(1);assertThat(s.sourceHoldId()).isEqualTo(hold);}
            finally{release.countDown();}
        }
    }
    @Test void sqlFailureReturnsNoPartialCountersOrExceptionDetails(){
        var unavailable=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        try{org.mockito.Mockito.when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("fixture-private-connection"));}catch(java.sql.SQLException impossible){throw new AssertionError(impossible);}
        var p=new CommerceRetentionHoldDeliveryProgress(unavailable,new DataSourceTransactionManager(unavailable)).inspect(10,intake.requestId(),intake.caseReference());assertThat(p.code()).isEqualTo(Code.UNAVAILABLE);assertThat(p.sources()).isEmpty();denyAuthority(p);
    }
    @Test void sevenHistoricalReceiptsDoNotConfirmCurrentSourcesOrCompleteCaseCoverage(){
        for(var p:CommerceRetentionSourceHolds.SOURCES)deliver(p,c->new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,UUID.randomUUID()));
        var observed=inspect();assertThat(observed.sources()).allSatisfy(s->assertThat(s.state()).isEqualTo(State.HISTORICAL_RECEIPT));denyAuthority(observed);assertThat(count("commerce_retention_hold_delivery_attempts")).isEqualTo(7);
    }
}
