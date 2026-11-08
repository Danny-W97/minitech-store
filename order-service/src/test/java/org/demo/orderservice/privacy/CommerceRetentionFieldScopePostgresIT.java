package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionFieldScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Testcontainers
class CommerceRetentionFieldScopePostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("field_review").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static DataSourceTransactionManager manager;static JdbcTemplate jdbc;
    CommerceRetentionJobs jobs;CommerceRetentionFieldScopeEvidence collector;
    @BeforeAll static void setup(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE commerce_retention_holds,commerce_retention_hold_scopes,commerce_retention_payment_evidence,commerce_retention_bindings,commerce_retention_evidence,commerce_retention_jobs,commerce_retention_order_state,account_privacy_fences,commerce_privacy_locks,orders RESTART IDENTITY CASCADE");jobs=new CommerceRetentionJobs(ds,manager);collector=new CommerceRetentionFieldScopeEvidence(ds,manager);seed(10,17L);seed(11,18L);seed(12,null);seed(13,19L);}
    void seed(long id,Long customer){jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES(?,?,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','COURIER',0,0,'HOME_DELIVERY',?,?,'fixture',now(),now())",id,customer,"field-"+id,"a".repeat(64));}
    Subject subject(long id){return jobs.prepare(id,Phase.REDACTION);}
    long evidence(){return jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class);}
    void chainAndOtherGuards(Subject s){var time=Instant.now().minus(400,ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);for(var p:Participant.values())jobs.record(new ChainProof(p,s,Phase.REDACTION,POLICY,State.CONFIRMED,time,null));for(var g:requiredGuards(Phase.REDACTION))if(g!=Guard.FIELD_SCOPE_REVIEWED)jobs.record(g,new GuardProof(s,Phase.REDACTION,POLICY,State.CONFIRMED));}
    void rawField(Subject s,String hash){jdbc.update("INSERT INTO commerce_retention_evidence(order_id,generation,phase,kind,source,policy,state,field_scope_hash) VALUES(?,?,'REDACTION','GUARD','FIELD_SCOPE_REVIEWED',?,'CONFIRMED',?)",s.orderId(),s.chainGeneration(),POLICY,hash);}
    Result assess(Subject s){jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day' WHERE order_id=? AND current",s.orderId());return jobs.assess(jobs.claim(s,Phase.REDACTION).orElseThrow()).orElseThrow();}
    @Test void reviewedDefinitionCreatesExactlyOneImmutableGuardAndNoOtherClearance(){
        var s=subject(10);var r=collector.collect(s,Phase.REDACTION);assertThat(r.code()).isEqualTo(CommerceRetentionFieldScopeEvidence.Code.RECORDED);assertThat(r.definitionHash()).isEqualTo(CommerceRetentionFieldScope.SHA256);assertThat(r.receipt()).isEqualTo(CommerceRetentionJobs.Receipt.STORED);assertThat(evidence()).isEqualTo(1);assertThat(jdbc.queryForMap("SELECT source,kind,state,field_scope_hash FROM commerce_retention_evidence")).containsEntry("source","FIELD_SCOPE_REVIEWED").containsEntry("kind","GUARD").containsEntry("state","CONFIRMED").containsEntry("field_scope_hash",CommerceRetentionFieldScope.SHA256);assertThat(assess(s).code()).isEqualTo(Code.MISSING_CHAIN_PROOF);
    }
    @Test void genericConfirmationAndCallerSuppliedHashCannotMintAFieldReview(){
        var s=subject(10);assertThatThrownBy(()->jobs.record(Guard.FIELD_SCOPE_REVIEWED,new GuardProof(s,Phase.REDACTION,POLICY,State.CONFIRMED))).hasMessage("Trusted field scope review required");assertThatThrownBy(()->jobs.recordReviewedFieldScope(s,Phase.REDACTION,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);assertThat(evidence()).isZero();
    }
    @Test void correctFieldReviewLeavesPreservationPolicyAndJournalUnresolved(){
        var s=subject(10);var time=Instant.now().minus(400,ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);for(var p:Participant.values())jobs.record(new ChainProof(p,s,Phase.REDACTION,POLICY,State.CONFIRMED,time,null));collector.collect(s,Phase.REDACTION);var r=assess(s);assertThat(r.code()).isEqualTo(Code.MISSING_GUARD_PROOF);assertThat(r.activationAllowed()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD' AND source<>'FIELD_SCOPE_REVIEWED'",Long.class)).isZero();
    }
    @Test void historicalNullAndDifferentDefinitionRemainUntrustedWithoutRewritingHistory(){
        for(long id:new long[]{10,11}){var s=subject(id);chainAndOtherGuards(s);String hash=id==10?null:"0".repeat(64);rawField(s,hash);var r=assess(s);assertThat(r.code()).isEqualTo(Code.MISSING_GUARD_PROOF);assertThat(r.activationAllowed()).isFalse();assertThat(jdbc.queryForMap("SELECT state,field_scope_hash FROM commerce_retention_evidence WHERE order_id=? AND source='FIELD_SCOPE_REVIEWED'",id)).containsEntry("state","CONFIRMED").containsEntry("field_scope_hash",hash);}
    }
    @Test void legacyConfirmationIsNotBackfilledAndRequiresANewGeneration(){
        var first=subject(10);rawField(first,null);var conflict=collector.collect(first,Phase.REDACTION);assertThat(conflict.code()).isEqualTo(CommerceRetentionFieldScopeEvidence.Code.STALE);assertThat(conflict.receipt()).isEqualTo(CommerceRetentionJobs.Receipt.CONFLICT);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();var next=subject(10);assertThat(next.chainGeneration()).isNotEqualTo(first.chainGeneration());assertThat(collector.collect(next,Phase.REDACTION).receipt()).isEqualTo(CommerceRetentionJobs.Receipt.STORED);assertThat(jdbc.queryForObject("SELECT field_scope_hash IS NULL FROM commerce_retention_evidence WHERE generation=?",Boolean.class,first.chainGeneration())).isTrue();
    }
    @Test void bindingHashStateAndHistoryCannotBeChangedOrDeleted(){
        var s=subject(10);collector.collect(s,Phase.REDACTION);for(String sql:List.of("UPDATE commerce_retention_evidence SET field_scope_hash=NULL","UPDATE commerce_retention_evidence SET field_scope_hash=repeat('0',64)","UPDATE commerce_retention_evidence SET policy='unreviewed'","DELETE FROM commerce_retention_evidence"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var legacy=subject(11);rawField(legacy,null);assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_evidence SET field_scope_hash=? WHERE order_id=11",CommerceRetentionFieldScope.SHA256)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void ownersVersionsPhasesAndUnknownScopesCannotAffectTheLegitimateJob(){
        var s=subject(10);for(var other:List.of(new Subject(10,18,0,s.chainGeneration()),new Subject(10,17,1,s.chainGeneration()),new Subject(10,17,0,UUID.randomUUID()),new Subject(999,0,0,UUID.randomUUID())))assertThat(collector.collect(other,Phase.REDACTION).code()).isEqualTo(CommerceRetentionFieldScopeEvidence.Code.STALE);assertThat(collector.collect(s,Phase.MINIMUM_REMOVAL).code()).isEqualTo(CommerceRetentionFieldScopeEvidence.Code.STALE);assertThat(evidence()).isZero();assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isTrue();
    }
    @Test void accountFencesKnownHoldsAndRestoredMetadataPreventNewConfirmations(){
        var a=subject(10);var b=subject(11);var c=subject(12);var d=subject(13);jdbc.update("INSERT INTO account_privacy_fences VALUES(17,?,'BLOCKED')",UUID.randomUUID());new CommerceRetentionHolds(ds,manager).placeCustomerPurchases(18,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);new CommerceRetentionBindings(ds,manager).prepare(c,Phase.REDACTION);jdbc.update("UPDATE commerce_retention_bindings SET restore_blocked=TRUE WHERE order_id=12");jdbc.update("UPDATE commerce_retention_order_state SET restore_blocked=TRUE WHERE order_id=13");for(var s:List.of(a,b,c,d))assertThat(collector.collect(s,Phase.REDACTION).code()).isEqualTo(CommerceRetentionFieldScopeEvidence.Code.BLOCKED);assertThat(evidence()).isZero();
    }
    @Test void guestAndMinimumFieldReviewDoNotInventARedactionReceipt(){
        var s=jobs.prepare(12,Phase.MINIMUM_REMOVAL);assertThat(s.customerId()).isZero();assertThat(collector.collect(s,Phase.MINIMUM_REMOVAL).code()).isEqualTo(CommerceRetentionFieldScopeEvidence.Code.RECORDED);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE redacted_at IS NOT NULL OR kind='CHAIN'",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT field_scope_hash FROM commerce_retention_evidence",String.class)).isEqualTo(CommerceRetentionFieldScope.SHA256);
    }
    @Test void firstReviewCancelsAnOldLeaseButIdempotentRepeatPreservesTheNewLease(){
        var s=subject(10);jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day'");var old=jobs.claim(s,Phase.REDACTION).orElseThrow();collector.collect(s,Phase.REDACTION);assertThat(jobs.assess(old)).isEmpty();jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day'");var fresh=jobs.claim(s,Phase.REDACTION).orElseThrow();assertThat(collector.collect(s,Phase.REDACTION).receipt()).isEqualTo(CommerceRetentionJobs.Receipt.IDEMPOTENT);assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_jobs",UUID.class)).isEqualTo(fresh.token());assertThat(jobs.assess(fresh).orElseThrow().activationAllowed()).isFalse();
    }
    @Test void workflowHasAnExplicitFieldReviewStepWithoutOtherGuardWrites(){
        var workflow=new CommerceRetentionWorkflow(ds,manager);var s=workflow.prepare(10,0,null,UUID.randomUUID(),Phase.REDACTION).subject();jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=1,source_revision=0,invalidated=FALSE");assertThat(workflow.reviewFieldScope(s,Phase.REDACTION).code()).isEqualTo(CommerceRetentionWorkflow.Code.FIELD_SCOPE_REVIEWED);assertThat(workflow.reviewFieldScope(s,Phase.REDACTION).activationAllowed()).isFalse();new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(()->workflow.reviewFieldScope(s,Phase.REDACTION)).hasMessage("RETENTION_WORKFLOW_CALLER_TRANSACTION"));assertThat(evidence()).isEqualTo(1);
    }
    @Test void retiringGenerationPreservesItsReviewedDefinitionWithoutReactivation(){
        var s=subject(10);collector.collect(s,Phase.REDACTION);jobs.invalidate(10,s.chainGeneration());assertThat(collector.collect(s,Phase.REDACTION).code()).isEqualTo(CommerceRetentionFieldScopeEvidence.Code.STALE);assertThat(jdbc.queryForObject("SELECT field_scope_hash FROM commerce_retention_evidence",String.class)).isEqualTo(CommerceRetentionFieldScope.SHA256);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();
    }
}
