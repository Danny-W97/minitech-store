package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.orderservice.privacy.CommerceRetentionWorkflow.Code;

@Testcontainers
class CommerceRetentionWorkflowPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("retention_workflow").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static DataSourceTransactionManager manager;static JdbcTemplate jdbc;
    CommerceRetentionWorkflow workflow;CommerceRetentionJobs jobs;CommerceRetentionHolds holds;
    final Instant sourceClock=Instant.now().minus(1,ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    static final String EVENT=PeerRetentionProtocol.eventHash(List.of("payment-"+"a".repeat(64)));
    @BeforeAll static void setup(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());manager=new DataSourceTransactionManager(ds);jdbc=new JdbcTemplate(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE commerce_retention_holds,commerce_retention_hold_scopes,commerce_retention_payment_evidence,commerce_retention_bindings,commerce_retention_evidence,commerce_retention_jobs,commerce_retention_order_state,account_privacy_fences,commerce_privacy_locks,orders RESTART IDENTITY CASCADE");workflow=new CommerceRetentionWorkflow(ds,manager);jobs=new CommerceRetentionJobs(ds,manager);holds=new CommerceRetentionHolds(ds,manager);seed(10,17L);seed(11,18L);seed(12,null);}
    void seed(long id,Long customer){jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,inventory_reservation_id,created_at,updated_at) VALUES(?,?,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY',?,?,'fixture',1,now(),now())",id,customer,"fixture-"+id,"a".repeat(64));jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,sku,quantity,unit_price,total_price) VALUES(?,1,'Fixture','FIX',1,10,10)",id);jdbc.update("INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES(?,'FAILED',now(),now())",id);}
    Subject prepare(long order){var r=workflow.prepare(order,0,null,UUID.randomUUID(),Phase.REDACTION);assertThat(r.code()).isEqualTo(Code.PREPARED);return r.subject();}
    void bound(Subject s){jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=expected_epoch+1,source_revision=0,invalidated=FALSE WHERE order_id=? AND generation=?",s.orderId(),s.chainGeneration());}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    InventoryRetentionProtocol.Reply inventory(InventoryRetentionProtocol.Command c){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,sourceClock);}
    PeerRetentionProtocol.Reply peer(PeerRetentionProtocol.Command c){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();boolean events=Set.of(Participant.PAYMENT,Participant.BILLING,Participant.NOTIFICATION).contains(c.scope().source());return PeerRetentionProtocol.reply(c,State.CONFIRMED,PeerRetentionProtocol.Code.OBSERVED,sourceClock,events?EVENT:null,events?1:0);}
    CommerceRetentionWorkflow.Step next(Subject s){return workflow.observeNext(s,Phase.REDACTION,this::inventory,this::peer);}
    void localObserved(Subject s){assertThat(next(s).participant()).isEqualTo(Participant.ORDER);}
    @Test void preparationIsAtomicAndRetryDoesNotResetCommandsOrEpochs(){
        UUID generation=UUID.randomUUID();var first=workflow.prepare(10,0,null,generation,Phase.REDACTION);assertThat(first.code()).isEqualTo(Code.PREPARED);assertThat(first.subject().chainGeneration()).isEqualTo(generation);assertThat(first.subject().customerId()).isEqualTo(17);assertThat(count("commerce_retention_bindings")).isEqualTo(7);
        jdbc.update("UPDATE commerce_retention_bindings SET attempts=3");assertThat(workflow.prepare(10,0,null,generation,Phase.REDACTION).code()).isEqualTo(Code.IDEMPOTENT);assertThat(count("commerce_retention_jobs")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT min(attempts) FROM commerce_retention_bindings",Integer.class)).isEqualTo(3);assertThat(count("commerce_retention_evidence")).isZero();
    }
    @Test void explicitPredecessorAndVersionPreventBlindReplacementOrResurrection(){
        var s=prepare(10);bound(s);assertThat(workflow.prepare(10,1,s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.STALE);assertThat(workflow.prepare(10,0,null,UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.STALE);
        var second=workflow.prepare(10,0,s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION);assertThat(second.code()).isEqualTo(Code.PREPARED);assertThat(workflow.prepare(10,0,null,s.chainGeneration(),Phase.REDACTION).code()).isEqualTo(Code.STALE);assertThat(workflow.prepare(10,0,s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.STALE);assertThat(workflow.prepare(10,0,null,second.subject().chainGeneration(),Phase.REDACTION).code()).isEqualTo(Code.STALE);assertThat(workflow.prepare(10,0,s.chainGeneration(),second.subject().chainGeneration(),Phase.REDACTION).code()).isEqualTo(Code.IDEMPOTENT);assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_jobs SET preparation_predecessor=NULL WHERE generation=?",second.subject().chainGeneration())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("commerce_retention_jobs")).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT min(expected_epoch) FROM commerce_retention_bindings WHERE generation=?",Long.class,second.subject().chainGeneration())).isEqualTo(1);
    }
    @Test void pendingPredecessorRollsBackNewGenerationAndRetirement(){
        var s=prepare(10);var result=workflow.prepare(10,0,s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION);assertThat(result.code()).isEqualTo(Code.PREDECESSOR_PENDING);assertThat(count("commerce_retention_jobs")).isEqualTo(1);assertThat(count("commerce_retention_bindings")).isEqualTo(7);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isTrue();
    }
    @Test void concurrentPreparationsHaveOneWinnerWithoutExtraGenerations()throws Exception{
        try(var executor=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Code>>();for(int i=0;i<2;i++)tasks.add(executor.submit(()->{start.await();return workflow.prepare(10,0,null,UUID.randomUUID(),Phase.REDACTION).code();}));start.countDown();assertThat(List.of(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(Code.PREPARED,Code.STALE);}
        assertThat(count("commerce_retention_jobs")).isEqualTo(1);assertThat(count("commerce_retention_bindings")).isEqualTo(7);
    }
    @Test void oneObservationPerCallResumesAcrossInstancesAndNeverWritesGuardClearance(){
        var s=prepare(10);bound(s);var expected=List.of(Participant.ORDER,Participant.PAYMENT,Participant.INVENTORY,Participant.RETURNS,Participant.PROMOTION,Participant.DELIVERY,Participant.BILLING,Participant.NOTIFICATION);int[] remote={0};
        for(var p:expected){workflow=new CommerceRetentionWorkflow(ds,manager);long before=count("commerce_retention_evidence");var step=workflow.observeNext(s,Phase.REDACTION,c->{remote[0]++;return inventory(c);},c->{remote[0]++;return peer(c);});assertThat(step.code()).as(p.name()).isEqualTo(Code.OBSERVED);assertThat(step.participant()).isEqualTo(p);assertThat(count("commerce_retention_evidence")).isEqualTo(before+1);assertThat(step.activationAllowed()).isFalse();}
        assertThat(remote[0]).isEqualTo(7);assertThat(next(s).code()).isEqualTo(Code.OBSERVATIONS_COMPLETE);assertThat(count("commerce_retention_evidence")).isEqualTo(8);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD'",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE source='ORDER'",String.class)).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day'");var assessed=workflow.assessOne(s,Phase.REDACTION);assertThat(assessed.code()).isEqualTo(Code.ASSESSED);assertThat(assessed.assessment().code()).isEqualTo(CommerceRetentionAssessment.Code.UNRECONCILED_PROCESS);assertThat(assessed.activationAllowed()).isFalse();assertThat(count("orders")).isEqualTo(3);
    }
    @Test void bindingsAndActiveProcessWaitWithoutCallingTransport(){
        var s=prepare(10);assertThat(workflow.observeNext(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.WAITING_BINDINGS);bound(s);jdbc.update("UPDATE orders SET status='CREATED'");assertThat(next(s).code()).isEqualTo(Code.STALE);
        var active=workflow.prepare(10,0,s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION).subject();bound(active);assertThat(workflow.observeNext(active,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.WAITING_PROCESS);
    }
    @Test void unavailableAndMalformedPeerResponsesDoNotAdvanceOrMintProofs(){
        var s=prepare(10);bound(s);localObserved(s);for(int i=0;i<2;i++){var step=workflow.observeNext(s,Phase.REDACTION,this::inventory,c->{throw new IllegalStateException("fixture transport unavailable");});assertThat(step.code()).isEqualTo(Code.UNAVAILABLE);assertThat(step.participant()).isEqualTo(Participant.PAYMENT);}assertThat(count("commerce_retention_evidence")).isEqualTo(1);
        assertThat(workflow.observeNext(s,Phase.REDACTION,this::inventory,c->null).code()).isEqualTo(Code.UNAVAILABLE);assertThat(next(s).participant()).isEqualTo(Participant.PAYMENT);
    }
    @Test void customerHoldDuringRemoteReadRetiresItsGenerationAndReturnsBlocked(){
        var s=prepare(10);bound(s);localObserved(s);var result=workflow.observeNext(s,Phase.REDACTION,this::inventory,c->{holds.placeCustomerPurchases(17,UUID.randomUUID(),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionHolds.Reason.OPEN_CASE);return peer(c);});assertThat(result.code()).isEqualTo(Code.BLOCKED);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();assertThat(workflow.prepare(10,0,s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.BLOCKED);
    }
    @Test void mutationDuringRemoteReadCannotAttachProofToStaleOwnerVersion(){
        var s=prepare(10);bound(s);localObserved(s);assertThat(workflow.observeNext(s,Phase.REDACTION,this::inventory,c->{jdbc.update("UPDATE orders SET version=version+1 WHERE id=10");return peer(c);}).code()).isEqualTo(Code.STALE);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE source='PAYMENT'",Long.class)).isZero();
    }
    @Test void restoreQuarantineAndAccountErasureFenceBlockPreparationOrFurtherReads(){
        var s=prepare(10);bound(s);jdbc.update("UPDATE commerce_retention_bindings SET restore_blocked=TRUE WHERE source='PAYMENT'");assertThat(workflow.observeNext(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.BLOCKED);assertThat(workflow.prepare(10,0,s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.BLOCKED);
        jdbc.update("INSERT INTO account_privacy_fences VALUES(18,?,'BLOCKED')",UUID.randomUUID());assertThat(workflow.prepare(11,0,null,UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.BLOCKED);
    }
    @Test void guestAndUnknownSubjectsDoNotShareIdentityOrCreateMetadataForUnknownOrders(){
        var s=prepare(12);assertThat(s.customerId()).isZero();bound(s);assertThat(next(s).participant()).isEqualTo(Participant.ORDER);assertThat(workflow.prepare(999,0,null,UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.STALE);assertThat(next(new Subject(999,0,0,UUID.randomUUID())).code()).isEqualTo(Code.STALE);assertThat(next(new Subject(12,17,0,s.chainGeneration())).code()).isEqualTo(Code.STALE);assertThat(workflow.assessOne(new Subject(12,17,0,s.chainGeneration()),Phase.REDACTION).code()).isEqualTo(Code.STALE);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE order_id=12",Boolean.class)).isTrue();assertThat(count("commerce_retention_jobs")).isEqualTo(1);
    }
    @Test void unsupportedPhaseAndCallerTransactionAreRejectedBeforeAnyPreparation(){
        assertThatThrownBy(()->workflow.prepare(10,0,null,UUID.randomUUID(),Phase.MINIMUM_REMOVAL)).isInstanceOf(IllegalArgumentException.class);new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(()->workflow.prepare(10,0,null,UUID.randomUUID(),Phase.REDACTION)).hasMessage("RETENTION_WORKFLOW_CALLER_TRANSACTION"));assertThat(count("commerce_retention_jobs")).isZero();
    }
    @Test void scopedClaimDoesNotLeaseOrExpireUnrelatedPurchase(){
        var s=prepare(10);bound(s);var other=prepare(11);bound(other);jdbc.update("UPDATE commerce_retention_jobs SET state='LEASED',attempts=8,run_token=?,run_until=clock_timestamp()-interval '1 day',next_attempt_at=clock_timestamp()-interval '2 days' WHERE order_id=11",UUID.randomUUID());jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day' WHERE order_id=10");
        var result=workflow.assessOne(s,Phase.REDACTION);assertThat(result.code()).isEqualTo(Code.ASSESSED);assertThat(result.assessment().code()).isEqualTo(CommerceRetentionAssessment.Code.MISSING_CHAIN_PROOF);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_jobs WHERE order_id=11",String.class)).isEqualTo("LEASED");assertThat(jobs.claim(new Subject(10,99,0,s.chainGeneration()),Phase.REDACTION)).isEmpty();
    }
    @Test void paymentUnknownCannotBeBypassedToMintBillingOrNotificationProofs(){
        var s=prepare(10);bound(s);localObserved(s);assertThat(workflow.observeNext(s,Phase.REDACTION,this::inventory,c->PeerRetentionProtocol.reply(c,State.UNKNOWN,PeerRetentionProtocol.Code.REVIEW_REQUIRED,null,null,0)).code()).isEqualTo(Code.OBSERVED);
        for(int i=0;i<4;i++)assertThat(next(s).code()).isEqualTo(Code.OBSERVED);var wait=workflow.observeNext(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();});assertThat(wait.code()).isEqualTo(Code.WAITING_DEPENDENCY);assertThat(wait.participant()).isEqualTo(Participant.BILLING);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE source IN ('BILLING','NOTIFICATION')",Long.class)).isZero();
    }

    Subject cached(long id){var s=prepare(id);bound(s);for(int i=0;i<8;i++)assertThat(next(s).code()).isEqualTo(Code.OBSERVED);jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day' WHERE order_id=? AND current",s.orderId());return s;}
    long revision(Subject s){return jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,s.chainGeneration());}
    UUID preparedIntent(Subject s){var id=UUID.randomUUID();assertThat(workflow.prepareIntent(s,Phase.REDACTION,id).code()).isEqualTo(CommerceRetentionIntents.Code.PREPARED);return id;}
    @Test void intentReviewReadsEightSourcesAndAssessesWithoutGrantingAuthorization(){
        var s=cached(10);var id=preparedIntent(s);long before=revision(s);int[] calls={0};
        var result=workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{calls[0]++;return inventory(c);},c->{calls[0]++;return peer(c);});
        assertThat(result.code()).isEqualTo(Code.INTENT_REVALIDATED_ASSESSED);assertThat(result.observations()).hasSize(8);assertThat(calls[0]).isEqualTo(7);
        assertThat(result.assessment()).isNotNull();assertThat(result.activationAllowed()).isFalse();assertThat(result.executionTerminated()).isFalse();
        assertThat(result.observations().get(Participant.ORDER).bindingEpoch()).isZero();
        for(var p:Participant.values())if(p!=Participant.ORDER)assertThat(result.observations().get(p)).isEqualTo(new CommerceRetentionWorkflow.Participation(1,0));
        assertThat(revision(s)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_intents WHERE intent_id=?",String.class,id)).isEqualTo("WAITING_AUTHORIZATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_intent_events",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE run_token IS NOT NULL",Long.class)).isZero();
    }
    @Test void incompleteCachedEvidenceDoesNotStartAnIntentSourceRound(){
        var s=prepare(10);bound(s);localObserved(s);var id=preparedIntent(s);
        var result=workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{throw new AssertionError();});
        assertThat(result.code()).isEqualTo(Code.WAITING_DEPENDENCY);assertThat(result.observations()).isEmpty();assertThat(result.assessment()).isNull();
    }
    @Test void changedPreparationProofRevisionAndUnknownIntentCannotBorrowEvidence(){
        var s=cached(10);var id=preparedIntent(s);workflow.reviewFieldScope(s,Phase.REDACTION);
        for(var target:List.of(id,UUID.randomUUID())){
            var result=workflow.revalidateIntentAndAssess(s,Phase.REDACTION,target,0,c->{throw new AssertionError();},c->{throw new AssertionError();});
            assertThat(result.code()).isEqualTo(Code.INTENT_STALE);assertThat(result.assessment()).isNull();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void stoppedOrCancelledIntentNeverStartsSourceReads(boolean cancel){
        var s=cached(10);var id=preparedIntent(s);
        if(cancel)workflow.cancelUnstartedIntent(s,Phase.REDACTION,id,0,UUID.randomUUID());else workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID());
        var result=workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{throw new AssertionError();});
        assertThat(result.code()).isEqualTo(Code.INTENT_STOPPED);assertThat(result.executionTerminated()).isFalse();assertThat(result.observations()).isEmpty();
    }
    @Test void stopDuringPeerCallPreventsRemainingReadsAndAssessmentLease(){
        var s=cached(10);var id=preparedIntent(s);int[] calls={0};
        var result=workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{
            calls[0]++;assertThat(workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID()).code()).isEqualTo(CommerceRetentionIntents.Code.UPDATED);return peer(c);
        });
        assertThat(result.code()).isEqualTo(Code.INTENT_STOPPED);assertThat(result.observations().keySet()).containsExactly(Participant.ORDER);assertThat(calls[0]).isEqualTo(1);assertThat(result.assessment()).isNull();
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs WHERE generation=?",Integer.class,s.chainGeneration())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE run_token IS NOT NULL",Long.class)).isZero();
    }
    @Test void wrongOwnerPhaseAndIntentRevisionAreRejectedBeforeRemoteCalls(){
        var s=cached(10);var id=preparedIntent(s);
        assertThat(workflow.revalidateIntentAndAssess(new Subject(10,18,s.orderVersion(),s.chainGeneration()),Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.INTENT_STALE);
        assertThat(workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,1,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.INTENT_STALE);
        assertThatThrownBy(()->workflow.revalidateIntentAndAssess(s,Phase.MINIMUM_REMOVAL,id,0,this::inventory,this::peer)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sourceFailureDoesNotConsumeAssessmentLeaseOrRetryBudget(){
        var s=cached(10);var id=preparedIntent(s);jdbc.update("UPDATE commerce_retention_jobs SET attempts=3 WHERE generation=?",s.chainGeneration());
        var before=jdbc.queryForMap("SELECT attempts,proof_revision,run_token,run_until,state FROM commerce_retention_jobs WHERE generation=?",s.chainGeneration());
        var result=workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{throw new IllegalStateException("fixture-private-error");});
        assertThat(result.code()).isEqualTo(Code.UNAVAILABLE);assertThat(result.assessment()).isNull();
        assertThat(jdbc.queryForMap("SELECT attempts,proof_revision,run_token,run_until,state FROM commerce_retention_jobs WHERE generation=?",s.chainGeneration())).isEqualTo(before);
    }
    @Test void concurrentStopWhilePeerIsInFlightDoesNotWaitForRemoteSqlTransaction()throws Exception{
        var s=cached(10);var id=preparedIntent(s);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var review=pool.submit(()->workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,this::inventory,c->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixture wait expired");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return peer(c);
            }));
            try{
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var stop=pool.submit(()->workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID()));
                assertThat(stop.get(5,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionIntents.Code.UPDATED);
                release.countDown();assertThat(review.get(10,TimeUnit.SECONDS).code()).isEqualTo(Code.INTENT_STOPPED);
            }finally{release.countDown();}
        }
    }
    @Test void knownHoldOrRestoreQuarantineBlocksIntentRound(){
        var s=cached(10);var id=preparedIntent(s);
        holds.placeCustomerPurchases(17,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        assertThat(workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.BLOCKED);
        jdbc.update("UPDATE commerce_retention_intents SET state='QUARANTINED',restore_blocked=TRUE,revision=revision+1,last_request_id=? WHERE intent_id=?",UUID.randomUUID(),id);
        assertThat(workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.INTENT_QUARANTINED);
    }
    @Test void finalAssessmentSqlFailureRollsBackItsLease(){
        var s=cached(10);var id=preparedIntent(s);
        var before=jdbc.queryForMap("SELECT state,attempts,run_token,run_until FROM commerce_retention_jobs WHERE generation=?",s.chainGeneration());
        jdbc.execute("CREATE FUNCTION fixture_assessment_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_assessment_failure BEFORE UPDATE ON commerce_retention_jobs FOR EACH ROW WHEN (OLD.state='LEASED' AND NEW.state<>'LEASED') EXECUTE FUNCTION fixture_assessment_failure()");
        try{
            var result=workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,this::inventory,this::peer);
            assertThat(result.code()).isEqualTo(Code.UNAVAILABLE);assertThat(result.assessment()).isNull();
        }finally{jdbc.execute("DROP TRIGGER fixture_assessment_failure ON commerce_retention_jobs");jdbc.execute("DROP FUNCTION fixture_assessment_failure()");}
        assertThat(jdbc.queryForMap("SELECT state,attempts,run_token,run_until FROM commerce_retention_jobs WHERE generation=?",s.chainGeneration())).isEqualTo(before);
    }
    @Test void existingLeaseAndCallerTransactionDoNotStartIntentReview(){
        var s=cached(10);var id=preparedIntent(s);var work=jobs.claim(s,Phase.REDACTION).orElseThrow();
        assertThat(workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.NOT_DUE);
        assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_jobs WHERE generation=?",UUID.class,s.chainGeneration())).isEqualTo(work.token());
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->workflow.revalidateIntentAndAssess(s,Phase.REDACTION,id,0,this::inventory,this::peer))).hasMessage("RETENTION_WORKFLOW_CALLER_TRANSACTION");
    }
    static class LeaseFixture implements CommerceRetentionWorkflow.FenceTransport {
        final List<PeerRetentionFenceProtocol.Command> calls=new ArrayList<>();
        final Instant until=Instant.now().plusSeconds(15);
        java.util.function.Function<PeerRetentionFenceProtocol.Command,PeerRetentionFenceProtocol.Reply> respond=this::normal;
        public PeerRetentionFenceProtocol.Reply fence(PeerRetentionFenceProtocol.Command c){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls.add(c);return respond.apply(c);
        }
        PeerRetentionFenceProtocol.Reply normal(PeerRetentionFenceProtocol.Command c){
            return c.action()==PeerRetentionFenceProtocol.Action.RELEASE
                ?PeerRetentionFenceProtocol.reply(c,PeerRetentionFenceProtocol.Status.RELEASED,null,null)
                :PeerRetentionFenceProtocol.reply(c,PeerRetentionFenceProtocol.Status.FENCED,Instant.now(),until);
        }
        long calls(PeerRetentionFenceProtocol.Action action){return calls.stream().filter(c->c.action()==action).count();}
    }
    CommerceRetentionWorkflow.IntentFenceReview fenced(Subject s,UUID id,CommerceRetentionWorkflow.FenceTransport f){
        return workflow.fenceIntentAndAssess(s,Phase.REDACTION,id,0,this::inventory,this::peer,f);
    }
    @Test void intentFencesCoverSevenPeersAndReleaseExactScopesWithoutAuthorization(){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();var result=fenced(s,id,leases);
        assertThat(result.review().code()).isEqualTo(Code.INTENT_FENCED_ASSESSED);assertThat(result.review().observations()).hasSize(8);
        assertThat(result.review().assessment()).isNotNull();assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);
        assertThat(result.releases()).hasSize(7);assertThat(result.releases().values()).containsOnly(CommerceRetentionWorkflow.ReleaseStatus.CONFIRMED);
        for(var action:PeerRetentionFenceProtocol.Action.values())assertThat(leases.calls(action)).isEqualTo(7);
        var scopes=leases.calls.stream().map(PeerRetentionFenceProtocol.Command::scope).toList();
        assertThat(scopes.stream().map(PeerRetentionFenceProtocol.Scope::fenceToken).distinct()).hasSize(1);
        assertThat(scopes.stream().map(PeerRetentionFenceProtocol.Scope::source).distinct()).contains(Participant.INVENTORY).doesNotContain(Participant.ORDER);
        for(var scope:scopes)assertThat(leases.calls.stream().filter(c->c.scope().equals(scope))).hasSize(3);
        assertThat(result.activationAllowed()).isFalse();assertThat(result.executionTerminated()).isFalse();
        assertThat(result.historyRecorded()).isTrue();
        var history=new CommerceRetentionObservationRounds(ds,manager).inspect(s,Phase.REDACTION,id,result.roundId()).orElseThrow();
        assertThat(history.finished()).isTrue();assertThat(history.leases()).hasSize(7);
        assertThat(history.leases().values()).allMatch(CommerceRetentionObservationRounds.Lease::releaseAcknowledged);
        assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_intents WHERE intent_id=?",String.class,id)).isEqualTo("WAITING_AUTHORIZATION");
        assertThat(count("commerce_retention_intent_events")).isEqualTo(1);assertThat(count("orders")).isEqualTo(3);
        assertThatThrownBy(()->result.releases().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=PeerRetentionFenceProtocol.Action.class,names={"PREPARE","VERIFY"})
    void intentStopDuringFenceRpcReleasesEveryAttemptedScope(PeerRetentionFenceProtocol.Action action){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();
        leases.respond=c->{if(c.action()==action && c.scope().source()==Participant.INVENTORY)
            assertThat(workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID()).code()).isEqualTo(CommerceRetentionIntents.Code.UPDATED);
            return leases.normal(c);};
        var result=fenced(s,id,leases);assertThat(result.review().code()).isEqualTo(Code.INTENT_STOPPED);
        assertThat(result.review().assessment()).isNull();assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);
        assertThat(leases.calls(PeerRetentionFenceProtocol.Action.PREPARE)).isEqualTo(action==PeerRetentionFenceProtocol.Action.PREPARE?2:7);
        assertThat(leases.calls(PeerRetentionFenceProtocol.Action.RELEASE)).isEqualTo(leases.calls(PeerRetentionFenceProtocol.Action.PREPARE));
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isZero();
    }
    @Test void intentLostPrepareReplyStillReleasesTheUncertainSource(){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();jdbc.update("UPDATE commerce_retention_jobs SET attempts=3");
        var before=jdbc.queryForMap("SELECT state,attempts,run_token,run_until FROM commerce_retention_jobs");
        leases.respond=c->{if(c.action()==PeerRetentionFenceProtocol.Action.PREPARE && c.scope().source()==Participant.INVENTORY)
            throw new IllegalStateException("fixture lost reply after possible commit");return leases.normal(c);};
        var result=fenced(s,id,leases);assertThat(result.review().code()).isEqualTo(Code.UNAVAILABLE);
        assertThat(result.releases().keySet()).containsExactlyInAnyOrder(Participant.PAYMENT,Participant.INVENTORY);
        assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);
        assertThat(jdbc.queryForMap("SELECT state,attempts,run_token,run_until FROM commerce_retention_jobs")).isEqualTo(before);
    }
    @Test void intentStopIsObservedEvenWhenPrepareResponseIsLost(){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();
        leases.respond=c->{if(c.action()==PeerRetentionFenceProtocol.Action.PREPARE){
            workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID());throw new IllegalStateException("fixture lost response");
        }return leases.normal(c);};
        var result=fenced(s,id,leases);assertThat(result.review().code()).isEqualTo(Code.INTENT_STOPPED);
        assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);assertThat(leases.calls).hasSize(2);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"exception","wrong-token","wrong-action","negative"})
    void intentUncertainReleaseDoesNotHideStopOrSkipOtherReleases(String failure){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();
        leases.respond=c->{
            if(c.action()==PeerRetentionFenceProtocol.Action.VERIFY && c.scope().source()==Participant.PAYMENT)
                workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID());
            if(c.action()==PeerRetentionFenceProtocol.Action.RELEASE && c.scope().source()==Participant.PAYMENT){
                if(failure.equals("exception"))throw new IllegalStateException("fixture lost release ack");
                if(failure.equals("negative"))return PeerRetentionFenceProtocol.reply(c,PeerRetentionFenceProtocol.Status.STALE,null,null);
                var scope=c.scope();
                return new PeerRetentionFenceProtocol.Reply(failure.equals("wrong-token")?new PeerRetentionFenceProtocol.Scope(scope.source(),scope.orderId(),scope.generation(),scope.orderVersion(),scope.phase(),scope.epoch(),scope.revision(),UUID.randomUUID()):scope,
                    failure.equals("wrong-action")?PeerRetentionFenceProtocol.Action.PREPARE:c.action(),PeerRetentionFenceProtocol.Status.RELEASED,null,null);
            }
            return leases.normal(c);
        };
        var result=fenced(s,id,leases);assertThat(result.review().code()).isEqualTo(Code.INTENT_STOPPED);
        assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.UNCONFIRMED);
        assertThat(result.releases()).hasSize(7).containsEntry(Participant.PAYMENT,CommerceRetentionWorkflow.ReleaseStatus.UNCONFIRMED);
        assertThat(leases.calls(PeerRetentionFenceProtocol.Action.RELEASE)).isEqualTo(7);assertThat(result.executionTerminated()).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"scope","hold","restore","cancel"})
    void intentFenceScopeLossReleasesUsingOriginalScope(String change){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();
        leases.respond=c->{if(c.action()==PeerRetentionFenceProtocol.Action.PREPARE && c.scope().source()==Participant.PAYMENT){
            switch(change){
                case "scope"->assertThat(workflow.prepare(s.orderId(),s.orderVersion(),s.chainGeneration(),UUID.randomUUID(),Phase.REDACTION).code()).isEqualTo(Code.PREPARED);
                case "hold"->holds.placeCustomerPurchases(17,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
                case "restore"->jdbc.update("UPDATE commerce_retention_intents SET state='QUARANTINED',restore_blocked=TRUE,revision=revision+1,last_request_id=?",UUID.randomUUID());
                case "cancel"->workflow.cancelUnstartedIntent(s,Phase.REDACTION,id,0,UUID.randomUUID());
            }}return leases.normal(c);};
        var result=fenced(s,id,leases);assertThat(result.review().code()).isEqualTo(switch(change){case "scope"->Code.INTENT_STALE;case "hold"->Code.BLOCKED;case "restore"->Code.INTENT_QUARANTINED;default->Code.INTENT_STOPPED;});
        assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);assertThat(leases.calls).hasSize(2);
        assertThat(leases.calls.get(1).scope()).isEqualTo(leases.calls.getFirst().scope());assertThat(result.review().assessment()).isNull();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"expired","renewed","wrong-source","negative"})
    void intentInvalidOrNegativeVerificationReleasesAllLeases(String failure){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();
        leases.respond=c->{if(c.action()==PeerRetentionFenceProtocol.Action.VERIFY && c.scope().source()==Participant.PAYMENT){
            if(failure.equals("negative"))return PeerRetentionFenceProtocol.reply(c,PeerRetentionFenceProtocol.Status.STALE,null,null);
            var normal=leases.normal(c);var scope=c.scope();
            return new PeerRetentionFenceProtocol.Reply(failure.equals("wrong-source")?new PeerRetentionFenceProtocol.Scope(Participant.INVENTORY,scope.orderId(),scope.generation(),scope.orderVersion(),scope.phase(),scope.epoch(),scope.revision(),scope.fenceToken()):scope,
                c.action(),normal.status(),failure.equals("expired")?Instant.now().minusSeconds(20):normal.fencedAt(),
                failure.equals("expired")?Instant.now().minusSeconds(1):failure.equals("renewed")?normal.expiresAt().plusSeconds(1):normal.expiresAt());
        }return leases.normal(c);};
        var result=fenced(s,id,leases);assertThat(result.review().code()).isEqualTo(failure.equals("negative")?Code.STALE:Code.UNAVAILABLE);
        assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);assertThat(result.review().assessment()).isNull();
        assertThat(leases.calls(PeerRetentionFenceProtocol.Action.RELEASE)).isEqualTo(7);
    }
    @Test void intentAssessmentSqlFailureRollsBackAndFinallyReleasesSevenLeases(){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();
        var before=jdbc.queryForMap("SELECT state,attempts,run_token,run_until FROM commerce_retention_jobs");
        jdbc.execute("CREATE FUNCTION fixture_fenced_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_fenced_failure BEFORE UPDATE ON commerce_retention_jobs FOR EACH ROW WHEN (OLD.state='LEASED' AND NEW.state<>'LEASED') EXECUTE FUNCTION fixture_fenced_failure()");
        try{
            var result=fenced(s,id,leases);assertThat(result.review().code()).isEqualTo(Code.UNAVAILABLE);
            assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);assertThat(result.review().assessment()).isNull();
        }finally{jdbc.execute("DROP TRIGGER fixture_fenced_failure ON commerce_retention_jobs");jdbc.execute("DROP FUNCTION fixture_fenced_failure()");}
        assertThat(leases.calls(PeerRetentionFenceProtocol.Action.RELEASE)).isEqualTo(7);
        assertThat(jdbc.queryForMap("SELECT state,attempts,run_token,run_until FROM commerce_retention_jobs")).isEqualTo(before);
    }
    @Test void intentFencesDoNotStartForStoppedIntentOrExistingLeaseOrCallerTransaction(){
        var s=cached(10);var id=preparedIntent(s);var leases=new LeaseFixture();var work=jobs.claim(s,Phase.REDACTION).orElseThrow();
        assertThat(fenced(s,id,leases).review().code()).isEqualTo(Code.NOT_DUE);assertThat(leases.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_jobs",UUID.class)).isEqualTo(work.token());
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->fenced(s,id,leases))).hasMessage("RETENTION_WORKFLOW_CALLER_TRANSACTION");
        workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID());var result=fenced(s,id,leases);
        assertThat(result.review().code()).isEqualTo(Code.INTENT_STOPPED);assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.NOT_REQUIRED);assertThat(leases.calls).isEmpty();
    }
    @Test void fullRoundReopensOnlyItsCachedAssessmentAfterEightUnchangedReads(){
        var s=cached(10);long revision=revision(s);jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',code='AGE_REACHED',next_attempt_at=clock_timestamp()+interval '1 day' WHERE generation=?",s.chainGeneration());int[] calls={0};
        var r=workflow.revalidateAndAssess(s,Phase.REDACTION,c->{calls[0]++;return inventory(c);},c->{calls[0]++;return peer(c);});assertThat(r.code()).isEqualTo(Code.REVALIDATED_ASSESSED);assertThat(r.assessment().code()).isEqualTo(CommerceRetentionAssessment.Code.UNRECONCILED_PROCESS);assertThat(r.activationAllowed()).isFalse();assertThat(calls[0]).isEqualTo(7);assertThat(revision(s)).isEqualTo(revision);assertThat(count("commerce_retention_evidence")).isEqualTo(8);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD'",Long.class)).isZero();
    }
    @Test void fullRoundRejectsIncompleteEvidenceBeforeCallingAnyRemoteSource(){
        var s=prepare(10);bound(s);localObserved(s);var r=workflow.revalidateAndAssess(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();});assertThat(r.code()).isEqualTo(Code.WAITING_DEPENDENCY);assertThat(count("commerce_retention_evidence")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE run_token IS NOT NULL",Long.class)).isZero();
    }
    @Test void unavailableSourceStopsFullRoundWithoutReturningTheCachedAssessment(){
        var s=cached(10);long revision=revision(s);jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',code='AGE_REACHED' WHERE generation=?",s.chainGeneration());int[] calls={0};var r=workflow.revalidateAndAssess(s,Phase.REDACTION,c->{throw new AssertionError();},c->{calls[0]++;throw new IllegalStateException("fixture unavailable");});assertThat(r.code()).isEqualTo(Code.UNAVAILABLE);assertThat(r.participant()).isEqualTo(Participant.PAYMENT);assertThat(r.assessment()).isNull();assertThat(calls[0]).isEqualTo(1);assertThat(revision(s)).isEqualTo(revision);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_jobs",String.class)).isEqualTo("DUE_FOR_REVIEW");
    }
    @Test void concurrentNewReviewRequiresAnotherRoundWithoutRetiringItsValidGeneration(){
        var s=cached(10);long initial=revision(s);var r=workflow.revalidateAndAssess(s,Phase.REDACTION,this::inventory,c->{if(c.scope().source()==Participant.PAYMENT)assertThat(workflow.reviewFieldScope(s,Phase.REDACTION).code()).isEqualTo(Code.FIELD_SCOPE_REVIEWED);return peer(c);});assertThat(r.code()).isEqualTo(Code.RETRY_REVALIDATION);assertThat(r.assessment()).isNull();assertThat(revision(s)).isEqualTo(initial+1);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isTrue();jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day'");assertThat(workflow.revalidateAndAssess(s,Phase.REDACTION,this::inventory,this::peer).code()).isEqualTo(Code.REVALIDATED_ASSESSED);
    }
    @Test void holdDuringFullRoundStopsAssessmentAndKeepsHistory(){
        var s=cached(10);var r=workflow.revalidateAndAssess(s,Phase.REDACTION,this::inventory,c->{holds.placeCustomerPurchases(17,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);return peer(c);});assertThat(r.code()).isEqualTo(Code.BLOCKED);assertThat(r.assessment()).isNull();assertThat(count("commerce_retention_evidence")).isEqualTo(8);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();
    }
    @Test void anotherActiveLeaseIsPreservedWithoutAFullRemoteRound(){
        var s=cached(10);jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day'");var lease=jobs.claim(s,Phase.REDACTION).orElseThrow();assertThat(workflow.revalidateAndAssess(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.NOT_DUE);assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_jobs",UUID.class)).isEqualTo(lease.token());assertThat(jobs.claimRevalidated(s,Phase.REDACTION,revision(s))).isEmpty();
    }
    @Test void fullRoundCannotResetAnExhaustedAttemptBudget(){
        var s=cached(10);jdbc.update("UPDATE commerce_retention_jobs SET state='FAILED',attempts=8,code='RETRY_LIMIT'");assertThat(workflow.revalidateAndAssess(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.NOT_DUE);assertThat(jobs.claimRevalidated(s,Phase.REDACTION,revision(s))).isEmpty();assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isEqualTo(8);
    }
    @Test void fullRoundAndTargetedRequeueRejectWrongScopePhaseAndCallerTransaction(){
        var s=cached(12);var wrong=new Subject(12,17,0,s.chainGeneration());assertThat(workflow.revalidateAndAssess(wrong,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.STALE);assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isTrue();assertThat(jobs.claimRevalidated(s,Phase.REDACTION,revision(s)+1)).isEmpty();assertThat(jobs.claimRevalidated(wrong,Phase.REDACTION,revision(s))).isEmpty();assertThatThrownBy(()->workflow.revalidateAndAssess(s,Phase.MINIMUM_REMOVAL,this::inventory,this::peer)).isInstanceOf(IllegalArgumentException.class);new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(()->workflow.revalidateAndAssess(s,Phase.REDACTION,this::inventory,this::peer)).hasMessage("RETENTION_WORKFLOW_CALLER_TRANSACTION"));
    }
    @Test void sourceQuarantineDuringFullRoundInvalidatesTheCachedGeneration(){
        var s=cached(10);var r=workflow.revalidateAndAssess(s,Phase.REDACTION,this::inventory,c->PeerRetentionProtocol.reply(c,State.UNKNOWN,PeerRetentionProtocol.Code.QUARANTINED,null,null,0));assertThat(r.code()).isEqualTo(Code.STALE);assertThat(r.assessment()).isNull();assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();
    }
    @Test void inventoryQuarantineStopsFullRoundBeforeLaterPeerCalls(){
        var s=cached(10);int[] calls={0};var r=workflow.revalidateAndAssess(s,Phase.REDACTION,c->InventoryRetentionProtocol.reply(c,State.UNKNOWN,InventoryRetentionProtocol.Code.QUARANTINED,null),c->{calls[0]++;return peer(c);});assertThat(r.code()).isEqualTo(Code.STALE);assertThat(r.participant()).isEqualTo(Participant.INVENTORY);assertThat(r.assessment()).isNull();assertThat(calls[0]).isEqualTo(1);
    }

    @Test void fullRoundRespectsFailureBackoffBeforeMakingRemoteCalls(){
        var s=cached(10);jdbc.update("UPDATE commerce_retention_jobs SET state='FAILED',attempts=3,code='ASSESSMENT_FAILED',next_attempt_at=clock_timestamp()+interval '1 day'");assertThat(workflow.revalidateAndAssess(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();}).code()).isEqualTo(Code.NOT_DUE);assertThat(jobs.claimRevalidated(s,Phase.REDACTION,revision(s))).isEmpty();assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isEqualTo(3);
    }

    @Test void forcedCachedClaimCanRecoverAfterACrashWithoutWaitingForItsOldFutureDate()throws Exception{
        var s=cached(10);jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',next_attempt_at=clock_timestamp()+interval '1 day'");var first=jobs.claimRevalidated(s,Phase.REDACTION,revision(s)).orElseThrow();assertThat(jdbc.queryForObject("SELECT next_attempt_at<run_until FROM commerce_retention_jobs",Boolean.class)).isTrue();jdbc.update("UPDATE commerce_retention_jobs SET run_until=clock_timestamp()-interval '1 day'");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT next_attempt_at<=clock_timestamp() FROM commerce_retention_jobs",Boolean.class)) && System.nanoTime()<deadline)Thread.sleep(20);
        var retry=jobs.claim(s,Phase.REDACTION).orElseThrow();assertThat(retry.token()).isNotEqualTo(first.token());assertThat(jobs.assess(first)).isEmpty();assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isEqualTo(2);
    }

    @Test void fullRoundCannotBypassRestoredBindingQuarantine(){
        var s=cached(10);jdbc.update("UPDATE commerce_retention_bindings SET restore_blocked=TRUE WHERE source='DELIVERY'");var r=workflow.revalidateAndAssess(s,Phase.REDACTION,c->{throw new AssertionError();},c->{throw new AssertionError();});assertThat(r.code()).isEqualTo(Code.BLOCKED);assertThat(r.assessment()).isNull();assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();
    }
    @Test void leaseAcquiredByAnotherWorkerDuringTheLastReadIsNotStolen(){
        var s=cached(10);var other=new java.util.concurrent.atomic.AtomicReference<CommerceRetentionJobs.Work>();var r=workflow.revalidateAndAssess(s,Phase.REDACTION,this::inventory,c->{if(c.scope().source()==Participant.NOTIFICATION)other.set(jobs.claim(s,Phase.REDACTION).orElseThrow());return peer(c);});assertThat(r.code()).isEqualTo(Code.NOT_DUE);assertThat(r.assessment()).isNull();assertThat(other.get()).isNotNull();assertThat(jdbc.queryForObject("SELECT run_token FROM commerce_retention_jobs",UUID.class)).isEqualTo(other.get().token());assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isEqualTo(1);
    }
    @Test void fenceAndAssessReachesFencedAssessedWhenPeersConfirm(){
        var s=cached(10);
        var r=workflow.fenceAndAssess(s,Phase.REDACTION,this::inventory,this::peer,c->new PeerRetentionFenceProtocol.Reply(c.scope(),c.action(),PeerRetentionFenceProtocol.Status.FENCED,Instant.now(),Instant.now().plusSeconds(15)));
        assertThat(r.code()).isEqualTo(Code.FENCED_ASSESSED);
        assertThat(r.assessment()).isNotNull();
        assertThat(r.activationAllowed()).isFalse();
    }
    @Test void fenceFailureDuringPrepareOrVerifyStopsAndInvalidatesGeneration(){
        var s=cached(10);
        var r=workflow.fenceAndAssess(s,Phase.REDACTION,this::inventory,this::peer,c->new PeerRetentionFenceProtocol.Reply(c.scope(),c.action(),c.action()==PeerRetentionFenceProtocol.Action.PREPARE?PeerRetentionFenceProtocol.Status.STALE:PeerRetentionFenceProtocol.Status.FENCED,null,null));
        assertThat(r.code()).isEqualTo(Code.STALE);
        assertThat(r.assessment()).isNull();
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,s.chainGeneration())).isFalse();
    }
    CommerceRetentionObservationRounds rounds(){return new CommerceRetentionObservationRounds(ds,manager);}
    PeerRetentionFenceProtocol.Scope leaseScope(Subject s,UUID round){return new PeerRetentionFenceProtocol.Scope(Participant.PAYMENT,s.orderId(),s.chainGeneration(),s.orderVersion(),Phase.REDACTION,1,0,round);}
    PeerRetentionFenceProtocol.Command release(PeerRetentionFenceProtocol.Scope scope){return new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,scope);}
    void acknowledge(Subject s,UUID intent,PeerRetentionFenceProtocol.Scope scope){var c=release(scope);assertThat(rounds().acknowledgeRelease(s,Phase.REDACTION,intent,c,PeerRetentionFenceProtocol.reply(c,PeerRetentionFenceProtocol.Status.RELEASED,null,null))).isTrue();}
    @Test void pendingAttemptSurvivesRestartAndBlocksAnotherRoundWithoutRecoveryRpc(){
        var s=cached(10);var id=preparedIntent(s);var round=UUID.randomUUID();
        assertThat(rounds().begin(s,Phase.REDACTION,id,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);
        assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,leaseScope(s,round))).isTrue();
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow();
        assertThat(snapshot.finished()).isFalse();assertThat(snapshot.leases().get(Participant.PAYMENT).releaseAcknowledged()).isFalse();
        assertThat(snapshot.activationAllowed()).isFalse();assertThat(snapshot.executionTerminated()).isFalse();
        var transport=new LeaseFixture();var result=fenced(s,id,transport);
        assertThat(result.review().code()).isEqualTo(Code.INTENT_ROUND_PENDING);assertThat(transport.calls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isZero();
    }
    @Test void retainedAttemptCannotBeResentAndReleaseAcknowledgementRequiresExactScope(){
        var s=cached(10);var id=preparedIntent(s);var round=UUID.randomUUID();var scope=leaseScope(s,round);
        assertThat(rounds().begin(s,Phase.REDACTION,id,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);
        assertThat(rounds().begin(s,Phase.REDACTION,id,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.IDEMPOTENT);
        assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,scope)).isTrue();
        assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,scope)).isFalse();
        assertThat(rounds().finish(s,Phase.REDACTION,id,round)).isFalse();
        var command=release(scope);var wrong=release(leaseScope(s,UUID.randomUUID()));
        assertThatThrownBy(()->rounds().acknowledgeRelease(s,Phase.REDACTION,id,command,PeerRetentionFenceProtocol.reply(wrong,PeerRetentionFenceProtocol.Status.RELEASED,null,null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(rounds().acknowledgeRelease(s,Phase.REDACTION,id,command,PeerRetentionFenceProtocol.reply(command,PeerRetentionFenceProtocol.Status.STALE,null,null))).isFalse();
        acknowledge(s,id,scope);acknowledge(s,id,scope);assertThat(rounds().finish(s,Phase.REDACTION,id,round)).isTrue();
        assertThat(rounds().finish(s,Phase.REDACTION,id,round)).isTrue();
        assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,scope)).isFalse();
        assertThat(rounds().inspect(new Subject(10,18,0,s.chainGeneration()),Phase.REDACTION,id,round)).isEmpty();
        assertThat(rounds().inspect(s,Phase.REDACTION,UUID.randomUUID(),round)).isEmpty();
        assertThatThrownBy(()->rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow().leases().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void sqlProtectsRoundHistoryAndRejectsFalseCompletionOrRetimedRows(){
        var s=cached(10);var id=preparedIntent(s);var round=UUID.randomUUID();rounds().begin(s,Phase.REDACTION,id,0,round);
        rounds().recordAttempt(s,Phase.REDACTION,id,0,leaseScope(s,round));
        for(String sql:List.of("DELETE FROM commerce_retention_observation_rounds","DELETE FROM commerce_retention_observation_leases",
                "UPDATE commerce_retention_observation_rounds SET finished_at=now()","UPDATE commerce_retention_observation_rounds SET created_at=now()-interval '1 day'",
                "UPDATE commerce_retention_observation_leases SET binding_epoch=binding_epoch+1"))
            assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->rounds().recordAttempt(s,Phase.REDACTION,id,0,new PeerRetentionFenceProtocol.Scope(Participant.INVENTORY,10,s.chainGeneration(),0,Phase.REDACTION,9,0,round))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        acknowledge(s,id,leaseScope(s,round));
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_observation_leases SET release_confirmed_at=NULL")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(rounds().finish(s,Phase.REDACTION,id,round)).isTrue();
    }
    @Test void technicalBudgetRetainsEightRoundsWithoutExpiryOrOverwritingHistory(){
        var s=cached(10);var id=preparedIntent(s);
        for(int i=1;i<=8;i++){
            var round=UUID.randomUUID();assertThat(rounds().begin(s,Phase.REDACTION,id,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);
            assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,leaseScope(s,round))).isTrue();acknowledge(s,id,leaseScope(s,round));
            assertThat(rounds().finish(s,Phase.REDACTION,id,round)).isTrue();assertThat(rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow().ordinal()).isEqualTo(i);
        }
        assertThat(rounds().begin(s,Phase.REDACTION,id,0,UUID.randomUUID())).isEqualTo(CommerceRetentionObservationRounds.Code.LIMIT_REACHED);
        assertThat(count("commerce_retention_observation_rounds")).isEqualTo(8);assertThat(count("commerce_retention_observation_leases")).isEqualTo(8);
    }
    @Test void concurrentRoundRegistrationHasOneWinnerWithoutDuplicateSlots()throws Exception{
        var s=cached(10);var id=preparedIntent(s);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return rounds().begin(s,Phase.REDACTION,id,0,UUID.randomUUID());});
            var b=pool.submit(()->{start.await();return rounds().begin(s,Phase.REDACTION,id,0,UUID.randomUUID());});start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(CommerceRetentionObservationRounds.Code.CREATED,CommerceRetentionObservationRounds.Code.PENDING_ROUND);
        }
        assertThat(count("commerce_retention_observation_rounds")).isEqualTo(1);
    }
    @Test void restoreQuarantinePreservesAcknowledgementsAndCannotBeReversed(){
        var s=cached(10);var id=preparedIntent(s);var round=UUID.randomUUID();rounds().begin(s,Phase.REDACTION,id,0,round);
        rounds().recordAttempt(s,Phase.REDACTION,id,0,leaseScope(s,round));acknowledge(s,id,leaseScope(s,round));
        jdbc.update("UPDATE commerce_retention_observation_rounds SET restore_blocked=TRUE");
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow();
        assertThat(snapshot.quarantined()).isTrue();assertThat(snapshot.leases().get(Participant.PAYMENT).releaseAcknowledged()).isTrue();
        assertThat(rounds().finish(s,Phase.REDACTION,id,round)).isFalse();
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_observation_rounds SET restore_blocked=FALSE")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void attemptRecordingFailurePreventsPrepareAndDoesNotConsumeJobBudget(){
        var s=cached(10);var id=preparedIntent(s);var transport=new LeaseFixture();
        jdbc.execute("CREATE FUNCTION fixture_attempt_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_attempt_failure BEFORE INSERT ON commerce_retention_observation_leases FOR EACH ROW EXECUTE FUNCTION fixture_attempt_failure()");
        try{
            var result=fenced(s,id,transport);assertThat(result.review().code()).isEqualTo(Code.UNAVAILABLE);assertThat(result.historyRecorded()).isFalse();
            assertThat(transport.calls).isEmpty();assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isZero();
        }finally{jdbc.execute("DROP TRIGGER fixture_attempt_failure ON commerce_retention_observation_leases");jdbc.execute("DROP FUNCTION fixture_attempt_failure()");}
    }
    @Test void releaseRecordingFailureKeepsPendingHistoryDespiteConfirmedRemoteCleanup(){
        var s=cached(10);var id=preparedIntent(s);var transport=new LeaseFixture();
        jdbc.execute("CREATE FUNCTION fixture_release_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_release_failure BEFORE UPDATE ON commerce_retention_observation_leases FOR EACH ROW WHEN (NEW.source='PAYMENT') EXECUTE FUNCTION fixture_release_failure()");
        CommerceRetentionWorkflow.IntentFenceReview result;
        try{result=fenced(s,id,transport);}finally{jdbc.execute("DROP TRIGGER fixture_release_failure ON commerce_retention_observation_leases");jdbc.execute("DROP FUNCTION fixture_release_failure()");}
        assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);assertThat(result.historyRecorded()).isFalse();
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,result.roundId()).orElseThrow();
        assertThat(snapshot.finished()).isFalse();assertThat(snapshot.leases().get(Participant.PAYMENT).releaseAcknowledged()).isFalse();
        jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day'");
        var second=new LeaseFixture();assertThat(fenced(s,id,second).review().code()).isEqualTo(Code.INTENT_ROUND_PENDING);assertThat(second.calls).isEmpty();
    }
    @Test void lostAttemptCommitAcknowledgementDoesNotSendPrepareOrAssumeRollback(){
        var s=cached(10);var id=preparedIntent(s);var lose=new java.util.concurrent.atomic.AtomicBoolean(true);
        var uncertain=new org.springframework.jdbc.datasource.DelegatingDataSource(ds){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                var connection=ds.getConnection();boolean[] attempt={false};
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql && sql.contains("INSERT INTO commerce_retention_observation_leases"))attempt[0]=true;
                    Object result;
                    try{result=method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("commit") && attempt[0] && lose.compareAndSet(true,false))throw new java.sql.SQLException("fixture commit acknowledgement lost","08006");
                    return result;
                });
            }
        };
        var uncertainWorkflow=new CommerceRetentionWorkflow(uncertain,new DataSourceTransactionManager(uncertain));var transport=new LeaseFixture();
        var result=uncertainWorkflow.fenceIntentAndAssess(s,Phase.REDACTION,id,0,this::inventory,this::peer,transport);
        assertThat(result.review().code()).isEqualTo(Code.UNAVAILABLE);assertThat(result.historyRecorded()).isFalse();assertThat(transport.calls).isEmpty();
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,result.roundId()).orElseThrow();
        assertThat(snapshot.finished()).isFalse();assertThat(snapshot.leases()).hasSize(1);
        assertThat(snapshot.leases().get(Participant.PAYMENT).releaseAcknowledged()).isFalse();
    }
    UUID pendingRound(Subject s,UUID id){var round=UUID.randomUUID();assertThat(rounds().begin(s,Phase.REDACTION,id,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,leaseScope(s,round))).isTrue();return round;}
    CommerceRetentionRoundRecovery recovery(){return new CommerceRetentionRoundRecovery(ds,manager);}
    PeerRetentionFenceProtocol.Reply released(PeerRetentionFenceProtocol.Command c){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(c.action()).isEqualTo(PeerRetentionFenceProtocol.Action.RELEASE);
        return PeerRetentionFenceProtocol.reply(c,PeerRetentionFenceProtocol.Status.RELEASED,null,null);
    }
    @Test void recoverySettlesOnlyRecordedPendingAttemptsAndNeverAssessesOrPrepares(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);
        var inventory=new PeerRetentionFenceProtocol.Scope(Participant.INVENTORY,10,s.chainGeneration(),0,Phase.REDACTION,1,0,round);
        assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,inventory)).isTrue();acknowledge(s,id,leaseScope(s,round));
        var calls=new ArrayList<Participant>();var result=recovery().recover(s,Phase.REDACTION,id,round,c->{calls.add(c.scope().source());return released(c);});
        assertThat(result.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);assertThat(calls).containsExactly(Participant.INVENTORY);
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow();assertThat(snapshot.finished()).isTrue();assertThat(snapshot.cleanupOnly()).isTrue();assertThat(snapshot.recoveryAttempts()).isEqualTo(1);
        assertThat(result.activationAllowed()).isFalse();assertThat(result.executionTerminated()).isFalse();
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isZero();assertThat(count("commerce_retention_intent_events")).isEqualTo(1);
        assertThat(recovery().recover(s,Phase.REDACTION,id,round,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.FINISHED);
    }
    @Test void recoveryFailureKeepsPendingHistoryForAnExplicitNewPass(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);
        assertThat(recovery().recover(s,Phase.REDACTION,id,round,c->{throw new IllegalStateException("fixture unavailable");}).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.RETRY_REQUIRED);
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow();assertThat(snapshot.finished()).isFalse();assertThat(snapshot.cleanupOnly()).isTrue();assertThat(snapshot.leases().get(Participant.PAYMENT).releaseAcknowledged()).isFalse();
        assertThat(recovery().recover(s,Phase.REDACTION,id,round,this::released).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);
        assertThat(rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow().recoveryAttempts()).isEqualTo(2);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"token","action","negative"})
    void recoveryRejectsMalformedOrNegativeAcknowledgements(String failure){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);
        var result=recovery().recover(s,Phase.REDACTION,id,round,c->{
            var scope=failure.equals("token")?leaseScope(s,UUID.randomUUID()):c.scope();
            return new PeerRetentionFenceProtocol.Reply(scope,failure.equals("action")?PeerRetentionFenceProtocol.Action.PREPARE:c.action(),failure.equals("negative")?PeerRetentionFenceProtocol.Status.STALE:PeerRetentionFenceProtocol.Status.RELEASED,null,null);
        });
        assertThat(result.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.RETRY_REQUIRED);
        assertThat(rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow().leases().get(Participant.PAYMENT).releaseAcknowledged()).isFalse();
    }
    @Test void recoveryOwnershipBlocksOldWriterAndRequiresSqlOwnerContext(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);var first=rounds().claimRecovery(s,Phase.REDACTION,id,round).work();
        assertThat(rounds().claimRecovery(s,Phase.REDACTION,id,round).code()).isEqualTo(CommerceRetentionObservationRounds.RecoveryCode.BUSY);
        assertThat(rounds().active(s,Phase.REDACTION,id,round)).isFalse();
        assertThat(rounds().recordAttempt(s,Phase.REDACTION,id,0,new PeerRetentionFenceProtocol.Scope(Participant.INVENTORY,10,s.chainGeneration(),0,Phase.REDACTION,1,0,round))).isFalse();
        var command=release(leaseScope(s,round));assertThat(rounds().acknowledgeRelease(s,Phase.REDACTION,id,command,released(command))).isFalse();assertThat(rounds().finish(s,Phase.REDACTION,id,round)).isFalse();
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_observation_leases SET release_confirmed_at=clock_timestamp()")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_observation_rounds SET cleanup_only=FALSE")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(rounds().yieldRecovery(first)).isTrue();var next=rounds().claimRecovery(s,Phase.REDACTION,id,round).work();
        assertThat(rounds().recoveryOwned(first)).isFalse();assertThat(rounds().yieldRecovery(first)).isFalse();assertThat(rounds().recoveryOwned(next)).isTrue();
    }
    @Test void expiredRecoveryOwnerCannotAcknowledgeOrFinishAfterTakeover()throws Exception{
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);var old=rounds().claimRecovery(s,Phase.REDACTION,id,round).work();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(rounds().recoveryOwned(old) && System.nanoTime()<deadline)Thread.sleep(50);
        assertThat(rounds().recoveryOwned(old)).isFalse();
        var claim=rounds().claimRecovery(s,Phase.REDACTION,id,round);assertThat(claim.code()).isEqualTo(CommerceRetentionObservationRounds.RecoveryCode.CLAIMED);
        var next=claim.work();assertThat(next).isNotNull();
        var command=release(leaseScope(s,round));assertThat(rounds().acknowledgeRelease(s,Phase.REDACTION,id,command,released(command),old.token())).isFalse();
        assertThat(rounds().finish(s,Phase.REDACTION,id,round,old.token())).isFalse();assertThat(rounds().yieldRecovery(old)).isFalse();
        assertThat(rounds().acknowledgeRelease(s,Phase.REDACTION,id,command,released(command),next.token())).isTrue();assertThat(rounds().finish(s,Phase.REDACTION,id,round,next.token())).isTrue();
    }
    @Test void recoveryBudgetIsBoundedAndDoesNotCreateAnotherGeneration(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);
        for(int i=0;i<8;i++){var work=rounds().claimRecovery(s,Phase.REDACTION,id,round).work();assertThat(work).isNotNull();assertThat(rounds().yieldRecovery(work)).isTrue();}
        assertThat(rounds().claimRecovery(s,Phase.REDACTION,id,round).code()).isEqualTo(CommerceRetentionObservationRounds.RecoveryCode.LIMIT_REACHED);
        assertThat(count("commerce_retention_jobs")).isEqualTo(1);assertThat(rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow().recoveryAttempts()).isEqualTo(8);
    }
    @Test void recoveryAcceptsStoppedScopeButRejectsWrongOwnerAndRestoreQuarantine(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);
        assertThat(recovery().recover(new Subject(10,18,0,s.chainGeneration()),Phase.REDACTION,id,round,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.STALE);
        workflow.requestIntentStop(s,Phase.REDACTION,id,0,UUID.randomUUID());assertThat(recovery().recover(s,Phase.REDACTION,id,round,this::released).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);
        jdbc.update("UPDATE commerce_retention_observation_rounds SET restore_blocked=TRUE");
        assertThat(recovery().recover(s,Phase.REDACTION,id,round,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.QUARANTINED);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=PeerRetentionFenceProtocol.Action.class,names={"PREPARE","VERIFY"})
    void originalWorkerCannotContinueAfterRecoveryWinsDuringRpc(PeerRetentionFenceProtocol.Action action){
        var s=cached(10);var id=preparedIntent(s);var transport=new LeaseFixture();
        transport.respond=c->{
            if(c.action()==action && c.scope().source()==Participant.PAYMENT){
                var recovered=recovery().recover(s,Phase.REDACTION,id,c.scope().fenceToken(),this::released);
                assertThat(recovered.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);
            }
            return transport.normal(c);
        };
        var result=fenced(s,id,transport);assertThat(result.review().code()).isEqualTo(Code.INTENT_ROUND_TAKEN_OVER);assertThat(result.review().assessment()).isNull();
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs",Integer.class)).isZero();assertThat(rounds().inspect(s,Phase.REDACTION,id,result.roundId()).orElseThrow().finished()).isTrue();
    }
    @Test void recoverySqlFailureCannotInventAcknowledgementAndAllowsExplicitRetry(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);
        jdbc.execute("CREATE FUNCTION fixture_recovery_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_recovery_failure BEFORE UPDATE ON commerce_retention_observation_leases FOR EACH ROW EXECUTE FUNCTION fixture_recovery_failure()");
        try{assertThat(recovery().recover(s,Phase.REDACTION,id,round,this::released).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.UNAVAILABLE);}
        finally{jdbc.execute("DROP TRIGGER fixture_recovery_failure ON commerce_retention_observation_leases");jdbc.execute("DROP FUNCTION fixture_recovery_failure()");}
        assertThat(rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow().leases().get(Participant.PAYMENT).releaseAcknowledged()).isFalse();
        assertThat(recovery().recover(s,Phase.REDACTION,id,round,this::released).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);
    }
    @Test void recoverySettlementRollbackKeepsAcknowledgementsAndExplicitRetrySendsNoRpc(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);var calls=new ArrayList<Participant>();
        jdbc.execute("CREATE FUNCTION fixture_settlement_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only settlement failure'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_settlement_failure BEFORE UPDATE ON commerce_retention_observation_rounds FOR EACH ROW WHEN (OLD.finished_at IS NULL AND NEW.finished_at IS NOT NULL) EXECUTE FUNCTION fixture_settlement_failure()");
        try{
            var result=recovery().recover(s,Phase.REDACTION,id,round,c->{calls.add(c.scope().source());return released(c);});
            assertThat(result.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.UNAVAILABLE);assertThat(result.acknowledgements()).containsEntry(Participant.PAYMENT,true);
        }finally{jdbc.execute("DROP TRIGGER fixture_settlement_failure ON commerce_retention_observation_rounds");jdbc.execute("DROP FUNCTION fixture_settlement_failure()");}
        assertThat(calls).containsExactly(Participant.PAYMENT);var snapshot=rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow();
        assertThat(snapshot.finished()).isFalse();assertThat(snapshot.leases().get(Participant.PAYMENT).releaseAcknowledged()).isTrue();
        assertThat(jdbc.queryForObject("SELECT recovery_token IS NULL AND recovery_until IS NULL FROM commerce_retention_observation_rounds",Boolean.class)).isTrue();
        var retried=recovery().recover(s,Phase.REDACTION,id,round,c->{throw new AssertionError("Confirmed source must not be released again");});
        assertThat(retried.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);assertThat(retried.acknowledgements()).isEmpty();
        assertThat(rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow().recoveryAttempts()).isEqualTo(2);
        assertThat(retried.activationAllowed()).isFalse();assertThat(retried.executionTerminated()).isFalse();
    }
    @Test void lostRecoverySettlementCommitAcknowledgementRequiresInspectionAndNeverRepeatsSourceRpc(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);var lose=new java.util.concurrent.atomic.AtomicBoolean(true);
        var uncertain=new org.springframework.jdbc.datasource.DelegatingDataSource(ds){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                var connection=ds.getConnection();boolean[] settlement={false};
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql && sql.contains("SET finished_at=clock_timestamp(),recovery_token=NULL"))settlement[0]=true;
                    Object result;try{result=method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("commit") && settlement[0] && lose.compareAndSet(true,false))throw new java.sql.SQLException("fixture settlement acknowledgement lost","08006");
                    return result;
                });
            }
        };
        var calls=new ArrayList<Participant>();var uncertainRecovery=new CommerceRetentionRoundRecovery(uncertain,new DataSourceTransactionManager(uncertain));
        var result=uncertainRecovery.recover(s,Phase.REDACTION,id,round,c->{calls.add(c.scope().source());return released(c);});
        assertThat(lose.get()).isFalse();assertThat(result.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.UNAVAILABLE);
        assertThat(result.acknowledgements()).containsEntry(Participant.PAYMENT,true);assertThat(calls).containsExactly(Participant.PAYMENT);
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow();assertThat(snapshot.finished()).isTrue();assertThat(snapshot.leases().get(Participant.PAYMENT).releaseAcknowledged()).isTrue();
        assertThat(recovery().recover(s,Phase.REDACTION,id,round,c->{throw new AssertionError("Finished round must not send RPC");}).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.FINISHED);
        assertThat(snapshot.recoveryAttempts()).isEqualTo(1);assertThat(result.activationAllowed()).isFalse();assertThat(result.executionTerminated()).isFalse();
    }
    @Test void lostRecoveryClaimCommitAcknowledgementSendsNoRpcAndDoesNotAssumeRollback(){
        var s=cached(10);var id=preparedIntent(s);var round=pendingRound(s,id);var lose=new java.util.concurrent.atomic.AtomicBoolean(true);
        var uncertain=new org.springframework.jdbc.datasource.DelegatingDataSource(ds){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                var connection=ds.getConnection();boolean[] claim={false};
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql && sql.contains("SET cleanup_only=TRUE,recovery_token="))claim[0]=true;
                    Object result;try{result=method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("commit") && claim[0] && lose.compareAndSet(true,false))throw new java.sql.SQLException("fixture claim acknowledgement lost","08006");
                    return result;
                });
            }
        };
        var uncertainRecovery=new CommerceRetentionRoundRecovery(uncertain,new DataSourceTransactionManager(uncertain));
        assertThat(uncertainRecovery.recover(s,Phase.REDACTION,id,round,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.UNAVAILABLE);
        var snapshot=rounds().inspect(s,Phase.REDACTION,id,round).orElseThrow();assertThat(snapshot.cleanupOnly()).isTrue();assertThat(snapshot.finished()).isFalse();assertThat(snapshot.recoveryAttempts()).isEqualTo(1);
        assertThat(rounds().active(s,Phase.REDACTION,id,round)).isFalse();assertThat(rounds().claimRecovery(s,Phase.REDACTION,id,round).code()).isEqualTo(CommerceRetentionObservationRounds.RecoveryCode.BUSY);
    }
}
