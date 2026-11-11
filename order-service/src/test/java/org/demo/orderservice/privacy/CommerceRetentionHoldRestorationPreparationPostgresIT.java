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
import org.demo.orderservice.privacy.CommerceRetentionHoldRestorationPreparation.Code;

import org.demo.orderservice.privacy.CommerceRetentionHoldRepairAssessment.State;
import java.time.Instant;

@Testcontainers
class CommerceRetentionHoldRestorationPreparationPostgresIT {
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
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void preparationUsesExactIntentSqlAndNeverApprovesCaseOrWritesMetadata(Participant source){
        UUID intent=intent(source);var result=prepare(source,intent,SourceHoldRestorationProtocol.Mode.RESTORE);assertThat(result.code()).isEqualTo(Code.COMMAND_PREPARED);assertThat(result.caseAuthorizationVerified()).isFalse();assertThat(result.activationAllowed()).isFalse();var c=result.command();assertThat(c.delivery().source()).isEqualTo(source);assertThat(c.delivery().requestId()).isEqualTo(intake.sourceRequests().get(source));assertThat(c.caseReference()).isEqualTo(intake.caseReference());assertThat(c.intentId()).isEqualTo(intent);assertThat(c.bindingEpoch()).isEqualTo(2);assertThat(c.sourceRevision()).isEqualTo(3);assertThat(c.holdId()).isEqualTo(jdbc.queryForObject("SELECT source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE source=?",UUID.class,source.name()));assertThat(result.toString()).doesNotContain("fixture-sensitive-authorization");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probe_results",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_repair_intents",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"order","request","case","source"})
    void mismatchedScopeCannotExposeCommand(String change){
        UUID intent=intent(Participant.PAYMENT);var p=preparation.prepare(change.equals("order")?11:10,change.equals("request")?UUID.randomUUID():intake.requestId(),change.equals("case")?UUID.randomUUID():intake.caseReference(),change.equals("source")?Participant.RETURNS:Participant.PAYMENT,intent,UUID.randomUUID(),"",SourceHoldRestorationProtocol.Mode.RESTORE);assertThat(p.code()).isEqualTo(Code.UNKNOWN_INTENT);assertThat(p.command()).isNull();
    }
    @Test void historicalExpiredIntentCanOnlyBuildReadOnlyRecoveryRequest(){
        UUID basis=put(Participant.PAYMENT,CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE,Instant.now().minusSeconds(60)),intent=UUID.randomUUID();jdbc.execute("ALTER TABLE commerce_retention_hold_repair_intents DISABLE TRIGGER USER");
        try{jdbc.update("INSERT INTO commerce_retention_hold_repair_intents(intent_id,request_id,source,basis_probe_id,source_request_id,source_hold_id) SELECT ?,request_id,source,probe_id,source_request_id,source_hold_id FROM commerce_retention_hold_probes WHERE probe_id=?",intent,basis);}finally{jdbc.execute("ALTER TABLE commerce_retention_hold_repair_intents ENABLE TRIGGER USER");}
        assertThat(prepare(Participant.PAYMENT,intent,SourceHoldRestorationProtocol.Mode.RESTORE).code()).isEqualTo(Code.RECHECK_REQUIRED);var read=prepare(Participant.PAYMENT,intent,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY);assertThat(read.code()).isEqualTo(Code.COMMAND_PREPARED);assertThat(read.command().mode()).isEqualTo(SourceHoldRestorationProtocol.Mode.REPLAY_ONLY);assertThat(read.caseAuthorizationVerified()).isFalse();
    }
    @Test void newUnresolvedPendingAfterIntentRequiresNewQualification(){UUID intent=intent(Participant.PAYMENT);put(Participant.PAYMENT,null,null);assertThat(prepare(Participant.PAYMENT,intent,SourceHoldRestorationProtocol.Mode.RESTORE).code()).isEqualTo(Code.RECHECK_REQUIRED);}
    @ParameterizedTest @ValueSource(strings={"parent","order-scope","customer-scope","order-state"})
    void quarantineBlocksWriteAndReadOnlyPreparation(String scope){
        UUID intent=intent(Participant.PAYMENT);switch(scope){case "parent"->jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");case "order-scope"->jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE");case "customer-scope"->jdbc.update("INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id,restore_blocked) VALUES('CUSTOMER',17,TRUE)");case "order-state"->jdbc.update("INSERT INTO commerce_retention_order_state(order_id,restore_blocked) VALUES(10,TRUE)");}
        for(var mode:SourceHoldRestorationProtocol.Mode.values()){var r=prepare(Participant.PAYMENT,intent,mode);assertThat(r.code()).isEqualTo(Code.QUARANTINED);assertThat(r.command()).isNull();}
    }
    @Test void invalidInputsAndCallerTransactionCannotCreateCommand(){
        UUID id=UUID.randomUUID();assertThatThrownBy(()->preparation.prepare(0,intake.requestId(),intake.caseReference(),Participant.PAYMENT,id,id,"",SourceHoldRestorationProtocol.Mode.RESTORE)).isInstanceOf(IllegalArgumentException.class);new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(()->prepare(Participant.PAYMENT,id,SourceHoldRestorationProtocol.Mode.RESTORE)).hasMessage("RETENTION_HOLD_RESTORATION_PREPARATION_CALLER_TRANSACTION"));
    }
    @Test void sqlFailureReturnsNoCommand(){
        var unavailable=org.mockito.Mockito.mock(javax.sql.DataSource.class);try{org.mockito.Mockito.when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("fixture-private"));}catch(java.sql.SQLException impossible){throw new AssertionError(impossible);}
        var r=new CommerceRetentionHoldRestorationPreparation(unavailable,new DataSourceTransactionManager(unavailable)).prepare(10,intake.requestId(),intake.caseReference(),Participant.PAYMENT,UUID.randomUUID(),UUID.randomUUID(),"",SourceHoldRestorationProtocol.Mode.RESTORE);assertThat(r.code()).isEqualTo(Code.UNAVAILABLE);assertThat(r.command()).isNull();
    }
}
