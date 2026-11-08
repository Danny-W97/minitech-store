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
import static org.demo.orderservice.privacy.CommerceRetentionHoldRepairAssessment.*;
import org.demo.orderservice.privacy.CommerceRetentionHoldRepairAssessment.Code;
import org.demo.orderservice.privacy.CommerceRetentionHoldRepairAssessment.State;
import java.time.Instant;

@Testcontainers
class CommerceRetentionHoldRepairAssessmentPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("hold_repair_assessment").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    CommerceRetentionHoldRepairAssessment assessment;CommerceRetentionHoldReconciliationHistory history;CommerceRetentionHoldDelivery sender;CommerceRetentionHoldIntake.Registration intake;
    @BeforeAll static void initialize(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_holds,commerce_retention_hold_scopes RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES(10,17,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY','fixture-10',?,'fixture',now(),now())","a".repeat(64));
        intake=new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        assessment=new CommerceRetentionHoldRepairAssessment(ds,manager);history=new CommerceRetentionHoldReconciliationHistory(ds,manager);sender=new CommerceRetentionHoldDelivery(ds,manager);
        for(var p:CommerceRetentionSourceHolds.SOURCES)sender.deliver(10,intake.requestId(),intake.caseReference(),p,c->new SourceHoldDeliveryProtocol.Reply(c,SourceHoldDeliveryProtocol.Code.RECORDED,UUID.randomUUID()));
    }
    Assessment assess(){return assessment.assess(10,intake.requestId(),intake.caseReference());}
    Source source(Assessment a,Participant p){return a.sources().stream().filter(s->s.source()==p).findFirst().orElseThrow();}
    UUID put(Participant p,CommerceRetentionHoldReconciliation.Code code,Instant time){
        int n=jdbc.queryForObject("SELECT count(*)+1 FROM commerce_retention_hold_probes WHERE request_id=? AND source=?",Integer.class,intake.requestId(),p.name());UUID probe=UUID.randomUUID();
        jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) SELECT request_id,source,?,?,source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source=?",n,probe,intake.requestId(),p.name());
        if(code!=null){boolean observed=Set.of(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,CommerceRetentionHoldReconciliation.Code.SOURCE_CONFLICT,CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN,CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED).contains(code);boolean versioned=observed && code!=CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN;
            jdbc.update("INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES(?,?,?,?,?)",probe,code.name(),observed?java.sql.Timestamp.from(time):null,versioned?2:0,versioned?3:0);
        }return probe;
    }
    void noAuthority(Assessment a){assertThat(a.historyOnly()).isTrue();assertThat(a.activationAllowed()).isFalse();assertThat(a.repairAllowed()).isFalse();assertThat(a.completeCaseCoverage()).isFalse();}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void missingHoldCandidateKeepsExactIdentityAndStillRequiresFreshCheckAndCase(Participant who){
        UUID probe=put(who,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());var a=assess();var s=source(a,who);assertThat(s.state()).isEqualTo(State.MISSING_HOLD_CANDIDATE);assertThat(s.probesUsed()).isEqualTo(1);assertThat(s.remainingProbes()).isEqualTo(7);assertThat(s.historicalDiscrepancies()).isEqualTo(1);
        var c=s.candidate();assertThat(c.source()).isEqualTo(who);assertThat(c.sourceRequestId()).isEqualTo(intake.sourceRequests().get(who));assertThat(c.expectedHoldId()).isEqualTo(jdbc.queryForObject("SELECT source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE source=?",UUID.class,who.name()));assertThat(c.basisProbeId()).isEqualTo(probe);assertThat(c.guard()).isEqualTo(Guard.NO_PRESERVATION_HOLD);assertThat(c.reason()).isEqualTo(CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);assertThat(c.bindingEpoch()).isEqualTo(2);assertThat(c.sourceRevision()).isEqualTo(3);assertThat(c.freshSourceCheckRequired()).isTrue();assertThat(c.verifiedCaseRequired()).isTrue();assertThat(c.executionAllowed()).isFalse();noAuthority(a);
        assertThat(a.sources().stream().filter(v->v.source()!=who)).allSatisfy(v->{assertThat(v.state()).isEqualTo(State.PROBE_REQUIRED);assertThat(v.candidate()).isNull();});assertThatThrownBy(()->a.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @EnumSource(CommerceRetentionHoldReconciliation.Code.class)
    void eachStoredOutcomeHasConservativeQualification(CommerceRetentionHoldReconciliation.Code code){
        put(Participant.PAYMENT,code,Instant.now());var a=assess();var s=source(a,Participant.PAYMENT);
        State expected=switch(code){case MATCH_AS_OF_READ->State.MATCH_AS_OF_PROBE;case MISSING_AT_SOURCE->State.MISSING_HOLD_CANDIDATE;case SOURCE_CONFLICT->State.CONFLICT_REVIEW;case SOURCE_UNKNOWN->State.UNKNOWN_PURCHASE_REVIEW;case SOURCE_QUARANTINED->State.SOURCE_QUARANTINED;case ROOT_QUARANTINED->State.ROOT_QUARANTINED;default->State.RECHECK_REQUIRED;};
        assertThat(s.state()).isEqualTo(expected);assertThat(s.candidate()!=null).isEqualTo(code==CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE);noAuthority(a);
    }
    @ParameterizedTest @ValueSource(strings={"order","request","case"})
    void exactScopeIsRequiredBeforeHistoryOrCandidateIsExposed(String change){
        put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());var a=assessment.assess(change.equals("order")?11:10,change.equals("request")?UUID.randomUUID():intake.requestId(),change.equals("case")?UUID.randomUUID():intake.caseReference());assertThat(a.code()).isEqualTo(Code.UNKNOWN_REQUEST);assertThat(a.sources()).isEmpty();noAuthority(a);
    }
    @Test void invalidInputAndCallerTransactionAreRejected(){
        assertThatThrownBy(()->assessment.assess(0,intake.requestId(),intake.caseReference())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->assessment.assess(10,null,intake.caseReference())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->assessment.assess(10,intake.requestId(),null)).isInstanceOf(IllegalArgumentException.class);
        new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(this::assess).hasMessage("RETENTION_HOLD_REPAIR_ASSESSMENT_CALLER_TRANSACTION"));
    }
    @ParameterizedTest @ValueSource(strings={"old-match","old-missing","future-match","future-missing"})
    void expiredOrFutureObservationRequiresNewProbeWithoutConsumingBudget(String value){
        put(Participant.PAYMENT,value.endsWith("match")?CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ:CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now().plusSeconds(value.startsWith("old")?-60:60));
        var s=source(assess(),Participant.PAYMENT);assertThat(s.state()).isEqualTo(State.RECHECK_REQUIRED);assertThat(s.candidate()).isNull();assertThat(s.remainingProbes()).isEqualTo(7);assertThat(count("commerce_retention_hold_probes")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"older","latest"})
    void anyPendingProbeBlocksCandidateEvenWhenAnotherProbeHasFinished(String where){
        if(where.equals("older"))put(Participant.PAYMENT,null,null);put(Participant.PAYMENT,where.equals("older")?CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ:CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());if(where.equals("latest"))put(Participant.PAYMENT,null,null);
        var s=source(assess(),Participant.PAYMENT);assertThat(s.state()).isEqualTo(State.PENDING_REVIEW);assertThat(s.pendingProbes()).isEqualTo(1);assertThat(s.probesUsed()).isEqualTo(2);assertThat(s.remainingProbes()).isEqualTo(6);assertThat(s.candidate()).isNull();assertThat(count("commerce_retention_hold_probe_results")).isEqualTo(1);
    }
    @Test void laterMatchDoesNotEraseEarlierDiscrepancyOrGrantClosure(){
        put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ,Instant.now());var a=assess();var s=source(a,Participant.PAYMENT);assertThat(s.state()).isEqualTo(State.MATCH_AS_OF_PROBE);assertThat(s.historicalDiscrepancies()).isEqualTo(1);assertThat(s.candidate()).isNull();assertThat(count("commerce_retention_hold_probe_results")).isEqualTo(2);noAuthority(a);
    }
    @ParameterizedTest @ValueSource(strings={"parent","order-scope","customer-scope","order-state"})
    void quarantineOverridesAllCandidatesAndPreservesCounters(String scope){
        put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now());
        switch(scope){case "parent"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "order-scope"->jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");case "customer-scope"->jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id,restore_blocked) VALUES('CUSTOMER',17,TRUE)");case "order-state"->jdbc.update("INSERT INTO commerce_retention_order_state(order_id,restore_blocked) VALUES(10,TRUE)");}
        var a=assess();assertThat(a.code()).isEqualTo(Code.QUARANTINED);assertThat(a.sources()).hasSize(7).allSatisfy(s->{assertThat(s.state()).isEqualTo(State.ROOT_QUARANTINED);assertThat(s.candidate()).isNull();});assertThat(source(a,Participant.PAYMENT).probesUsed()).isEqualTo(1);noAuthority(a);
    }
    @Test void requestWithoutReceiptsRequiresDeliveryNotImplicitProbeOrBinding(){
        var other=new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);var a=assessment.assess(10,other.requestId(),other.caseReference());assertThat(a.sources()).hasSize(7).allSatisfy(s->{assertThat(s.state()).isEqualTo(State.NO_HISTORICAL_RECEIPT);assertThat(s.candidate()).isNull();});assertThat(count("commerce_retention_hold_probes")).isZero();noAuthority(a);
    }
    @Test void fullBoundedHistoryIsInspectedWithoutResetOrMetadataWrites(){
        for(var p:CommerceRetentionSourceHolds.SOURCES)for(int i=0;i<8;i++)put(p,CommerceRetentionHoldReconciliation.Code.UNAVAILABLE,null);
        for(int i=0;i<2;i++){var a=assess();assertThat(a.sources()).hasSize(7).allSatisfy(s->{assertThat(s.probesUsed()).isEqualTo(8);assertThat(s.remainingProbes()).isZero();assertThat(s.historicalDiscrepancies()).isEqualTo(8);assertThat(s.candidate()).isNull();});noAuthority(a);}
        assertThat(count("commerce_retention_hold_probes")).isEqualTo(56);assertThat(count("commerce_retention_hold_probe_results")).isEqualTo(56);assertThat(count("commerce_retention_hold_delivery_attempts")).isEqualTo(7);
    }
    @Test void inFlightObservationIsPendingUntilItsOwnCommit()throws Exception{
        var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);UUID probe=UUID.randomUUID();var calls=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->history.observe(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,probe,c->{calls.incrementAndGet();arrived.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return new SourceHoldInspectionProtocol.Reply(c,SourceHoldInspectionProtocol.Code.MISSING_HOLD,Instant.now(),2,3);}));
            try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();assertThat(source(assess(),Participant.PAYMENT).state()).isEqualTo(State.PENDING_REVIEW);}finally{release.countDown();}
            future.get(5,TimeUnit.SECONDS);assertThat(source(assess(),Participant.PAYMENT).state()).isEqualTo(State.MISSING_HOLD_CANDIDATE);assertThat(calls).hasValue(1);
        }
    }
    @Test void sqlFailureReturnsNoPartialQualificationOrPrivateDetails(){
        var unavailable=org.mockito.Mockito.mock(javax.sql.DataSource.class);try{org.mockito.Mockito.when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("fixture-private"));}catch(java.sql.SQLException impossible){throw new AssertionError(impossible);}
        var a=new CommerceRetentionHoldRepairAssessment(unavailable,new DataSourceTransactionManager(unavailable)).assess(10,intake.requestId(),intake.caseReference());assertThat(a.code()).isEqualTo(Code.UNAVAILABLE);assertThat(a.sources()).isEmpty();noAuthority(a);
    }
    @ParameterizedTest @ValueSource(strings={"gap","identity"})
    void malformedHistoricalSequenceOrReceiptIdentityFailsClosed(String change){
        jdbc.execute("ALTER TABLE commerce_retention_hold_probes DISABLE TRIGGER USER");
        try{jdbc.update("INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) SELECT request_id,source,?,?,?,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source='PAYMENT'",change.equals("gap")?2:1,UUID.randomUUID(),change.equals("identity")?intake.sourceRequests().get(Participant.RETURNS):intake.sourceRequests().get(Participant.PAYMENT),intake.requestId());}finally{jdbc.execute("ALTER TABLE commerce_retention_hold_probes ENABLE TRIGGER USER");}
        var a=assess();assertThat(a.code()).isEqualTo(Code.INCONSISTENT_HISTORY);assertThat(a.sources()).isEmpty();noAuthority(a);
    }
}
