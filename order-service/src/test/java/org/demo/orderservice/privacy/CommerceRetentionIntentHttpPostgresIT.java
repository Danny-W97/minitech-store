package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.*;
import static org.demo.orderservice.privacy.CommerceRetentionPeersPostgresIT.*;

/** Complete coordinator round: seven authenticated loopback sources, eight actual PostgreSQL databases. */
@Testcontainers
class CommerceRetentionIntentHttpPostgresIT {
    @Container static final PostgreSQLContainer<?> HTTP_DB=new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("intent_http_root").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource inventoryDs;static DataSourceTransactionManager inventoryManager;static JdbcTemplate inventorySql;
    static RetentionIntentHttpFixture.InventorySource inventorySource;
    final CommerceRetentionPeersPostgresIT data=new CommerceRetentionPeersPostgresIT();
    CommerceRetentionWorkflow workflow;Subject subject;UUID intent;

    @BeforeAll static void initializeHttpSources()throws Exception{
        initialize(HTTP_DB);
        Path root=Path.of("").toAbsolutePath();if(!Files.isDirectory(root.resolve("inventory-service")))root=root.getParent();
        jdbc.execute("CREATE DATABASE intent_inventory");
        inventoryDs=new DriverManagerDataSource(HTTP_DB.getJdbcUrl().replace("/intent_http_root","/intent_inventory"),HTTP_DB.getUsername(),HTTP_DB.getPassword());
        inventoryManager=new DataSourceTransactionManager(inventoryDs);inventorySql=new JdbcTemplate(inventoryDs);
        Flyway.configure().dataSource(inventoryDs).locations("filesystem:"+root.resolve("inventory-service/src/main/resources/db/migration")).load().migrate();
        inventorySource=new RetentionIntentHttpFixture.InventorySource(root,inventoryDs,inventoryManager);
    }
    @AfterAll static void closeInventoryClasses()throws Exception{if(inventorySource!=null)inventorySource.close();}
    @BeforeEach void prepareActualInventoryAndNewGeneration(){
        data.reset();
        inventorySql.execute("TRUNCATE inventory_reservations,inventory_reservation_items,commerce_retention_source_state,commerce_retention_change_outbox,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");
        var outbox=new CommerceRetentionSourceOutbox(inventoryDs,inventoryManager);
        UUID first=jdbc.queryForObject("SELECT generation FROM commerce_retention_bindings WHERE source='INVENTORY' AND expected_epoch=0",UUID.class);
        outbox.bind(new Subject(10,17,0,first),Phase.REDACTION,0);
        new TransactionTemplate(inventoryManager).executeWithoutResult(status->{
            inventorySql.update("INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES(1,10,'RESERVED',now(),now(),now()+interval '1 hour')");
            inventorySql.update("INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) VALUES(1,1,2)");
            inventorySql.update("UPDATE inventory_reservations SET status='RELEASED' WHERE id=1");
        });
        outbox.bind(data.scope,Phase.REDACTION,1);
        workflow=new CommerceRetentionWorkflow(ds,manager);
        var prepared=workflow.prepare(10,0,data.scope.chainGeneration(),UUID.randomUUID(),Phase.REDACTION);
        assertThat(prepared.code()).isEqualTo(CommerceRetentionWorkflow.Code.PREPARED);subject=prepared.subject();
        for(var participant:Participant.values()){
            if(participant==Participant.ORDER)continue;
            var bound=(participant==Participant.INVENTORY?outbox:outboxes.get(participant)).bind(subject,Phase.REDACTION,2);
            assertThat(jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=?,source_revision=?,invalidated=FALSE WHERE generation=? AND source=?",
                bound.epoch(),bound.revision(),subject.chainGeneration(),participant.name())).isEqualTo(1);
        }
        for(var p:PeerRetentionProtocol.PEERS)data.awaitSourceClock(p);
        Instant inventoryClock=inventorySql.queryForObject("SELECT local_reconciled_at FROM commerce_retention_source_state",java.sql.Timestamp.class).toInstant();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(inventoryClock.isAfter(Instant.now()) && System.nanoTime()<deadline){
            try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        }
        assertThat(inventoryClock).isBeforeOrEqualTo(Instant.now());
    }
    RetentionIntentHttpFixture http()throws Exception{
        var receivers=new EnumMap<Participant,SourceHoldDeliveryReceiver>(Participant.class);
        var inspectors=new EnumMap<Participant,SourceHoldInspector>(Participant.class);
        var restorers=new EnumMap<Participant,SourceHoldRestorer>(Participant.class);
        for(var source:CommerceRetentionSourceHolds.SOURCES){var m=sourceManager(source);receivers.put(source,new SourceHoldDeliveryReceiver(source,m.getDataSource(),m));inspectors.put(source,new SourceHoldInspector(source,m.getDataSource(),m));}
        for(var source:CommerceRetentionSourceHolds.SOURCES){var m=sourceManager(source);restorers.put(source,new SourceHoldRestorer(source,m.getDataSource(),m,RestorationCaseFixture.verifier()));}
        return new RetentionIntentHttpFixture(peers,inventorySource.controller,receivers,inspectors,restorers);
    }
    void prepareIntent(RetentionIntentHttpFixture http){
        for(int i=0;i<8;i++)assertThat(workflow.observeNext(subject,Phase.REDACTION,http::collect,http::collect).code()).isEqualTo(CommerceRetentionWorkflow.Code.OBSERVED);
        jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day' WHERE current");
        intent=UUID.randomUUID();assertThat(workflow.prepareIntent(subject,Phase.REDACTION,intent).code()).isEqualTo(CommerceRetentionIntents.Code.PREPARED);
        http.calls.clear();
    }
    CommerceRetentionWorkflow.IntentFenceReview review(RetentionIntentHttpFixture http){
        return workflow.fenceIntentAndAssess(subject,Phase.REDACTION,intent,0,
            c->{outsideSql();return http.collect(c);},c->{outsideSql();return http.collect(c);},c->{outsideSql();return http.fence(c);});
    }
    static void outsideSql(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    long calls(RetentionIntentHttpFixture http,Action action){return http.calls.stream().filter(c->c.command()!=null && c.command().action()==action).count();}
    void assertAllReleased(){
        for(var p:Participant.values())if(p!=Participant.ORDER){
            var sql=p==Participant.INVENTORY?inventorySql:db.get(p);
            assertThat(sql.queryForObject("SELECT count(*) FROM commerce_retention_source_state WHERE retention_fence_token IS NOT NULL",Long.class)).as(p.name()).isZero();
        }
    }
    PeerRetentionGuardProtocol.Command inventoryGuardCommand(){
        var s=sourceScope(Participant.INVENTORY,UUID.randomUUID());
        return new PeerRetentionGuardProtocol.Command(new PeerRetentionProtocol.Scope(s.source(),s.orderId(),s.generation(),s.orderVersion(),s.phase(),s.epoch(),s.revision()),POLICY,UUID.randomUUID());
    }
    DataSourceTransactionManager sourceManager(Participant p){return p==Participant.INVENTORY?inventoryManager:managers.get(p);}
    CommerceRetentionSourceHolds holdRegistry(Participant p){var m=sourceManager(p);return new CommerceRetentionSourceHolds(p,m.getDataSource(),m);}
    PeerRetentionGuardProtocol.Command sourceGuardCommand(Participant p){
        var s=sourceScope(p,UUID.randomUUID());
        return new PeerRetentionGuardProtocol.Command(new PeerRetentionProtocol.Scope(p,s.orderId(),s.generation(),s.orderVersion(),s.phase(),s.epoch(),s.revision()),POLICY,UUID.randomUUID());
    }
    void rebindSourceAfterHold(Participant source){
        var previous=subject;var prepared=workflow.prepare(10,0,previous.chainGeneration(),UUID.randomUUID(),Phase.REDACTION);
        assertThat(prepared.code()).isEqualTo(CommerceRetentionWorkflow.Code.PREPARED);subject=prepared.subject();
        long epoch=sourceSql(source).queryForObject("SELECT binding_epoch FROM commerce_retention_source_state WHERE order_id=10",Long.class);
        var bound=(source==Participant.INVENTORY?new CommerceRetentionSourceOutbox(inventoryDs,inventoryManager):outboxes.get(source)).bind(subject,Phase.REDACTION,epoch);
        assertThat(jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=?,source_revision=?,invalidated=FALSE WHERE generation=? AND source=?",bound.epoch(),bound.revision(),subject.chainGeneration(),source.name())).isEqualTo(1);
    }
    static java.util.stream.Stream<Arguments> durableHoldCases(){
        return PeerRetentionGuardProtocol.SOURCES.stream().flatMap(p->CommerceRetentionSourceHolds.GUARDS.stream().map(g->Arguments.of(p,g)));
    }
    @ParameterizedTest @MethodSource("durableHoldCases")
    void durableSourceHoldSurvivesRebindingAndBlocksThroughActualHttp(Participant source,Guard guard)throws Exception{
        try(var http=http()){
            var old=sourceGuardCommand(source);holdRegistry(source).place(10,UUID.randomUUID(),guard,CommerceRetentionSourceHolds.Reason.DEPENDENCY_REVIEW);
            assertThat(http.guards(old).code()).isEqualTo(PeerRetentionGuardProtocol.Code.STALE);rebindSourceAfterHold(source);
            var c=sourceGuardCommand(source);var observed=http.guards(c);assertThat(observed.states().get(guard)).isEqualTo(State.BLOCKED);
            assertThat(observed.states().values()).doesNotContain(State.CONFIRMED);
            assertThat(new CommerceRetentionSourceGuardCollector(ds,manager).collect(subject,Phase.REDACTION,source,command->{outsideSql();return http.guards(command);})).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
            assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE generation=? AND kind='GUARD' AND source=?",String.class,subject.chainGeneration(),guard.name())).isEqualTo("BLOCKED");
            assertThat(jdbc.queryForObject("SELECT hold_state FROM commerce_retention_source_receipts WHERE generation=? AND source=?",String.class,subject.chainGeneration(),source.name())).isEqualTo(guard==Guard.NO_PRESERVATION_HOLD?"BLOCKED":"UNKNOWN");
            assertThat(new CommerceRetentionSourceGuardCollector(ds,manager).collect(subject,Phase.REDACTION,source,http::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts WHERE generation=?",Long.class,subject.chainGeneration())).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void durableSourceHoldRollbackReplayAndConflictPreserveOneRevision(Participant source){
        var q=sourceSql(source);var initial=q.queryForMap("SELECT * FROM commerce_retention_source_state WHERE order_id=10");
        var outbox=q.queryForList("SELECT * FROM commerce_retention_change_outbox ORDER BY order_id,binding_epoch");
        new TransactionTemplate(sourceManager(source)).executeWithoutResult(tx->{holdRegistry(source).place(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);tx.setRollbackOnly();});
        assertThat(q.queryForMap("SELECT * FROM commerce_retention_source_state WHERE order_id=10")).isEqualTo(initial);
        assertThat(q.queryForList("SELECT * FROM commerce_retention_change_outbox ORDER BY order_id,binding_epoch")).isEqualTo(outbox);
        var request=UUID.randomUUID();var id=holdRegistry(source).place(10,request,Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
        assertThat(holdRegistry(source).place(10,request,Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST)).isEqualTo(id);
        assertThatThrownBy(()->holdRegistry(source).place(10,request,Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionSourceHolds.Reason.OPEN_CASE)).hasMessage("RETENTION_SOURCE_HOLD_REQUEST_CONFLICT");
        long expected=((Number)initial.get("source_revision")).longValue()+1;
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(expected);
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_holds",Long.class)).isEqualTo(expected);
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_change_outbox WHERE order_id=10 AND binding_epoch=?",Long.class,initial.get("binding_epoch"))).isEqualTo(expected);
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void durableSourceHoldHistoryAndRevisionRejectTampering(Participant source){
        var q=sourceSql(source);holdRegistry(source).place(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
        for(String sql:List.of("DELETE FROM commerce_retention_source_holds","UPDATE commerce_retention_source_holds SET reason_code='OPEN_CASE'","UPDATE commerce_retention_source_holds SET source_revision=99"))
            assertThatThrownBy(()->q.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->q.update("INSERT INTO commerce_retention_source_holds(hold_id,order_id,guard,reason_code,request_id,source_revision) VALUES(?,10,'NO_PRESERVATION_HOLD','PRESERVATION_REQUEST',?,99)",UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->holdRegistry(source).place(999,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST)).isInstanceOf(IllegalArgumentException.class);
        assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void durableSourceHoldInvalidatesObservationButKeepsBusinessFence(Participant source)throws Exception{
        try(var http=http()){
            var scope=sourceScope(source,UUID.randomUUID());assertThat(http.fence(new Command(Action.PREPARE,scope)).status()).isEqualTo(Status.FENCED);
            holdRegistry(source).place(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
            assertThat(http.fence(new Command(Action.VERIFY,scope)).status()).isEqualTo(Status.STALE);
            assertThat(sourceSql(source).queryForObject("SELECT retention_fence_token FROM commerce_retention_source_state WHERE order_id=10",UUID.class)).isEqualTo(scope.fenceToken());
            String sql=switch(source){
                case RETURNS->"UPDATE return_requests SET description='fenced'";
                case PAYMENT->"UPDATE payments SET result_recorded_at=clock_timestamp()";
                case INVENTORY->"UPDATE inventory_reservation_items SET quantity=3";
                case PROMOTION->"UPDATE coupon_reservations SET discount_amount=1";
                case DELIVERY->"UPDATE shipments SET sync_state='PENDING'";
                case BILLING->"UPDATE invoices SET buyer_name='fenced'";
                case NOTIFICATION->"UPDATE notifications SET message='fenced'";
                default->throw new AssertionError();};
            assertThatThrownBy(()->sourceSql(source).update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void durableSourceHoldConcurrentDuplicateIsOneStoredCommand(Participant source)throws Exception{
        var q=sourceSql(source);long before=q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class);var request=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);Callable<UUID> task=()->{assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();return holdRegistry(source).place(10,request,Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);};
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(before+1);
    }

    CommerceRetentionVersionedHoldInspection.Result versionedInspection(javax.sql.DataSource data,SourceHoldRestorationProtocol.Command c,CommerceRetentionHoldReviewVersions.Snapshot v,UUID step,CommerceRetentionHoldReconciliation.Transport remote){
        awaitCapturedVersion(v.reviewId());UUID request=jdbc.queryForObject("SELECT request_id FROM commerce_retention_hold_repair_intents WHERE intent_id=?",UUID.class,c.intentId());
        return new CommerceRetentionVersionedHoldInspection(data,new DataSourceTransactionManager(data)).inspect(10,request,c.caseReference(),v.reviewId(),v.versionHash(),c.delivery().source(),c.intentId(),step,remote);
    }
    CommerceRetentionHoldReviewVersions.Snapshot inspectionVersion(SourceHoldRestorationProtocol.Command c){UUID request=jdbc.queryForObject("SELECT request_id FROM commerce_retention_hold_repair_intents WHERE intent_id=?",UUID.class,c.intentId());var r=reviewVersions().capture(10,request,c.caseReference(),UUID.randomUUID());assertThat(r.code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.CAPTURED);return r.snapshot();}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void versionedInspectionReadsEachActualSourceOnce(Participant source)throws Exception{
        try(var http=http()){var c=restoration(http,source);rootRestore(rootRestoration(),c,http::restoreHold);var v=inspectionVersion(c);UUID step=UUID.randomUUID();int calls=http.calls.size();
            var r=versionedInspection(ds,c,v,step,x->{outsideSql();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_steps WHERE step_id=?",Integer.class,step)).isEqualTo(1);return http.inspectHold(x);});
            assertThat(r.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.RECORDED);assertThat(r.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);assertThat(http.calls).hasSize(calls+1);
            assertThat(versionedInspection(ds,c,v,step,x->{throw new AssertionError();})).isEqualTo(r);assertThat(http.calls).hasSize(calls+1);assertThat(r.activationAllowed()).isFalse();}
    }
    @Test void versionedInspectionUnknownReservationCommitNeverSendsOrRetries()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);var v=inspectionVersion(c);UUID id=UUID.randomUUID();var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var data=deliveryCommitLoss("INSERT INTO commerce_retention_hold_review_steps(",lose);
            var r=versionedInspection(data,c,v,id,x->{throw new AssertionError();});assertThat(lose.get()).isFalse();assertThat(r.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.UNAVAILABLE);
            assertThat(versionedInspection(ds,c,v,id,x->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.PENDING);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probe_results WHERE probe_id=?",Integer.class,id)).isZero();}
    }
    @Test void versionedInspectionUnknownResultCommitIsRecoveredWithoutSending()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);var v=inspectionVersion(c);UUID id=UUID.randomUUID();var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var data=deliveryCommitLoss("INSERT INTO commerce_retention_hold_probe_results(",lose);
            assertThat(versionedInspection(data,c,v,id,http::inspectHold).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.UNAVAILABLE);assertThat(lose.get()).isFalse();
            var r=versionedInspection(ds,c,v,id,x->{throw new AssertionError();});assertThat(r.code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.RECORDED);assertThat(r.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE);}
    }
    @Test void versionedInspectionConcurrentRetryCannotDispatchAgain()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){var c=restoration(http,Participant.PAYMENT);var v=inspectionVersion(c);UUID id=UUID.randomUUID();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=pool.submit(()->versionedInspection(ds,c,v,id,x->{var reply=http.inspectHold(x);holdResponse(arrived,release);return reply;}));
            try{assertThat(arrived.await(6,TimeUnit.SECONDS)).isTrue();assertThat(versionedInspection(ds,c,v,id,x->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.PENDING);}finally{release.countDown();}
            assertThat(first.get(6,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionVersionedHoldInspection.Code.RECORDED);}
    }
    CommerceRetentionHoldReviewVersions reviewVersions(){return new CommerceRetentionHoldReviewVersions(ds,manager);}
    CommerceRetentionHoldReviewVersions.Result captureVersion(SevenRestorations s,UUID id){return reviewVersions().capture(10,s.intake().requestId(),s.intake().caseReference(),id);}
    void awaitCapturedVersion(UUID id){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp()>reviewed_at+interval '20 milliseconds' FROM commerce_retention_hold_review_versions WHERE review_id=?",Boolean.class,id))){assertThat(System.nanoTime()).isLessThan(end);try{Thread.sleep(2);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}}
    CommerceRetentionHoldReviewVersions.Result checkVersion(SevenRestorations s,CommerceRetentionHoldReviewVersions.Snapshot version){awaitCapturedVersion(version.reviewId());return reviewVersions().check(10,s.intake().requestId(),s.intake().caseReference(),version.reviewId(),version.versionHash());}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void reviewVersionNewInspectionFromEachActualSourceInvalidatesOldVersion(Participant source)throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,false);var captured=captureVersion(scenario,UUID.randomUUID());assertThat(captured.code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.CAPTURED);assertThat(checkVersion(scenario,captured.snapshot()).code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.CURRENT_LOCAL);restorationProgress(restorationProgress(),scenario.commands().get(source),UUID.randomUUID(),http::inspectHold);assertThat(checkVersion(scenario,captured.snapshot()).code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.HISTORY_CHANGED);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_review_versions",Integer.class)).isEqualTo(1);}
    }
    @Test void reviewVersionCaptureRetryAndCheckDoNotCallSourcesOrRenewLease()throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,false);UUID id=UUID.randomUUID();int calls=http.calls.size();var first=captureVersion(scenario,id);var repeated=captureVersion(scenario,id);assertThat(repeated.snapshot()).isEqualTo(first.snapshot());assertThat(repeated.review()).isNull();assertThat(checkVersion(scenario,first.snapshot()).code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.CURRENT_LOCAL);assertThat(http.calls).hasSize(calls);assertThat(first.activationAllowed()).isFalse();assertThat(first.currentAuthority()).isFalse();assertThat(first.repairExecutionAllowed()).isFalse();}
    }
    @Test void reviewVersionCurrentLocalNeverClaimsUndetectedRemoteChangeIsSafe()throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,false);var version=captureVersion(scenario,UUID.randomUUID()).snapshot();sourceSql(Participant.PAYMENT).update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE WHERE order_id=10");var local=checkVersion(scenario,version);assertThat(local.code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.CURRENT_LOCAL);assertThat(local.currentAuthority()).isFalse();assertThat(local.completeCaseCoverage()).isFalse();restorationProgress(restorationProgress(),scenario.commands().get(Participant.PAYMENT),UUID.randomUUID(),http::inspectHold);assertThat(checkVersion(scenario,version).code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.HISTORY_CHANGED);}
    }
    @Test void reviewVersionLostCaptureCommitDoesNotReturnPreviewOrRenewOnRetry()throws Exception{
        var r=deliveryIntake();UUID id=UUID.randomUUID();var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var uncertain=deliveryCommitLoss("INSERT INTO commerce_retention_hold_review_versions(",lose);var result=new CommerceRetentionHoldReviewVersions(uncertain,new DataSourceTransactionManager(uncertain)).capture(10,r.requestId(),r.caseReference(),id);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.UNAVAILABLE);assertThat(result.snapshot()).isNull();assertThat(result.review()).isNull();assertThat(lose.get()).isFalse();var retry=reviewVersions().capture(10,r.requestId(),r.caseReference(),id);assertThat(retry.code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.ALREADY_CAPTURED);assertThat(retry.snapshot().ordinal()).isEqualTo(1);assertThat(retry.review()).isNull();awaitCapturedVersion(id);assertThat(reviewVersions().check(10,r.requestId(),r.caseReference(),id,retry.snapshot().versionHash()).code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.CURRENT_LOCAL);
    }
    @Test void reviewVersionLeaseIsCheckedAgainAfterReadingHistory(){
        var r=deliveryIntake();var s=reviewVersions().capture(10,r.requestId(),r.caseReference(),UUID.randomUUID()).snapshot();awaitCapturedVersion(s.reviewId());var clocks=new java.util.concurrent.atomic.AtomicInteger();var shifted=new DelegatingDataSource(ds){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{var actual=ds.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,a)->{Object value;try{value=m.invoke(actual,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}if(m.getName().equals("createStatement")){var statement=(java.sql.Statement)value;return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Statement.class},(p2,m2,a2)->{Object returned;try{returned=m2.invoke(statement,a2);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}if(m2.getName().equals("executeQuery") && a2[0].equals("SELECT clock_timestamp()")){var rows=(java.sql.ResultSet)returned;int n=clocks.incrementAndGet();return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(p3,m3,a3)->{if(n==2 && m3.getName().equals("getTimestamp"))return java.sql.Timestamp.from(s.expiresAt().plusMillis(1));try{return m3.invoke(rows,a3);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}return returned;});}return value;});}};var result=new CommerceRetentionHoldReviewVersions(shifted,new DataSourceTransactionManager(shifted)).check(10,r.requestId(),r.caseReference(),s.reviewId(),s.versionHash());assertThat(clocks.get()).isEqualTo(2);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReviewVersions.Code.EXPIRED);assertThat(result.currentAuthority()).isFalse();
    }
    CommerceRetentionHoldRestorationProgress.Refresh fixtureFreshProgress(SourceHoldRestorationProtocol.Command c,RetentionIntentHttpFixture http){
        // Fixture setup explicitly obtains a causally ordered read. A host/database
        // clock rollback remains OLDER_THAN_REPAIR in production; never relabel it.
        for(int n=0;n<7;n++){
            var r=restorationProgress(restorationProgress(),c,UUID.randomUUID(),x->{outsideSql();return http.inspectHold(x);});
            assertThat(r.probe().outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);
            if(r.progress().inspection()==CommerceRetentionHoldRestorationProgress.Inspection.MATCH_AS_OF_READ)return r;
            assertThat(r.progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.OLDER_THAN_REPAIR);
            assertThat(jdbc.queryForObject("SELECT p.created_at<GREATEST(i.created_at,COALESCE((SELECT max(created_at) FROM commerce_retention_hold_restoration_attempts WHERE intent_id=i.intent_id),'epoch'::timestamptz),COALESCE((SELECT max(r.recorded_at) FROM commerce_retention_hold_restoration_results r JOIN commerce_retention_hold_restoration_attempts a USING(attempt_id) WHERE a.intent_id=i.intent_id),'epoch'::timestamptz)) FROM commerce_retention_hold_repair_intents i JOIN commerce_retention_hold_probes p ON p.probe_id=? WHERE i.intent_id=?",Boolean.class,r.probe().probeId(),c.intentId())).as("Only a genuinely older database timestamp permits another explicit fixture inspection").isTrue();
        }
        throw new AssertionError("Fixture could not obtain an ordered inspection within the existing eight-probe budget");
    }
    record SevenRestorations(CommerceRetentionHoldIntake.Registration intake,Map<Participant,SourceHoldRestorationProtocol.Command> commands,int additionalProbes){}
    SevenRestorations sevenRestorations(RetentionIntentHttpFixture http,boolean uncertainPayment){
        var r=deliveryIntake();var commands=new EnumMap<Participant,SourceHoldRestorationProtocol.Command>(Participant.class);int additional=0;
        for(var source:CommerceRetentionSourceHolds.SOURCES){deliver(r,source,http::deliverHold);sourceSql(source).execute("TRUNCATE commerce_retention_source_holds CASCADE");new CommerceRetentionHoldReconciliationHistory(ds,manager).observe(10,r.requestId(),r.caseReference(),source,UUID.randomUUID(),http::inspectHold);UUID intent=UUID.randomUUID();assertThat(new CommerceRetentionHoldRecoveryPlanning(ds,manager).prepare(10,r.requestId(),r.caseReference(),source,intent).code()).isEqualTo(CommerceRetentionHoldRecoveryPlanning.Code.PREPARED);var c=RestorationCaseFixture.authorize(new CommerceRetentionHoldRestorationPreparation(ds,manager).prepare(10,r.requestId(),r.caseReference(),source,intent,UUID.randomUUID(),"",SourceHoldRestorationProtocol.Mode.RESTORE).command());commands.put(source,c);http.loseRestorationReply=uncertainPayment && source==Participant.PAYMENT?source:null;rootRestore(rootRestoration(),c,http::restoreHold);http.loseRestorationReply=null;additional+=fixtureFreshProgress(c,http).progress().probesUsed()-2;}
        return new SevenRestorations(r,commands,additional);
    }
    CommerceRetentionHoldRestorationReview.Review sevenReview(SevenRestorations s){return new CommerceRetentionHoldRestorationReview(ds,manager).review(10,s.intake().requestId(),s.intake().caseReference());}
    CommerceRetentionHoldRestorationReview.Source reviewedSource(CommerceRetentionHoldRestorationReview.Review r,Participant source){return r.sources().stream().filter(s->s.source()==source).findFirst().orElseThrow();}
    @Test void restorationReviewSevenActualSourcesAreReadWithoutExtraRpcOrGlobalClearance()throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,false);int calls=http.calls.size();var r=sevenReview(scenario);assertThat(http.calls).hasSize(calls);assertThat(r.code()).isEqualTo(CommerceRetentionHoldRestorationReview.Code.REVIEWED);assertThat(r.totals().sources()).isEqualTo(7);assertThat(r.totals().sourcesWithFreshMatches()).isEqualTo(7);assertThat(r.totals().reviewSources()).isZero();assertThat(r.totals().probes()).isEqualTo(14+scenario.additionalProbes());assertThat(r.totals().attempts()).isEqualTo(7);assertThat(r.totals().historicalAcknowledgements()).isEqualTo(7);assertThat(r.distributedSnapshot()).isFalse();assertThat(r.currentAuthority()).isFalse();assertThat(r.completeCaseCoverage()).isFalse();assertThat(r.repairExecutionAllowed()).isFalse();assertThat(r.activationAllowed()).isFalse();}
    }
    @Test void restorationReviewSevenMatchesDoNotHideLostPaymentWriteReply()throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,true);var r=sevenReview(scenario);assertThat(r.totals().pendingWrites()).isEqualTo(1);assertThat(r.totals().historicalAcknowledgements()).isEqualTo(6);assertThat(r.totals().sourcesWithFreshMatches()).isEqualTo(7);assertThat(reviewedSource(r,Participant.PAYMENT).reasons()).contains(CommerceRetentionHoldRestorationReview.Reason.UNKNOWN_WRITE);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_results",Integer.class)).isEqualTo(6);}
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void restorationReviewOneQuarantinedSourceRetainsAllOtherDiagnoses(Participant source)throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,false);sourceSql(source).update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE WHERE order_id=10");restorationProgress(restorationProgress(),scenario.commands().get(source),UUID.randomUUID(),http::inspectHold);var r=sevenReview(scenario);assertThat(r.sources()).hasSize(7);assertThat(r.totals().sourcesWithFreshMatches()).isEqualTo(6);assertThat(reviewedSource(r,source).reasons()).contains(CommerceRetentionHoldRestorationReview.Reason.SOURCE_DISCREPANCY);assertThat(reviewedSource(r,source).intents().getFirst().progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.SOURCE_QUARANTINED);}
    }
    @Test void restorationReviewOneStopRemainsVisibleAlongsideSixMatches()throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,false);var c=scenario.commands().get(Participant.PAYMENT);rootRestoration().stop(10,scenario.intake().requestId(),scenario.intake().caseReference(),Participant.PAYMENT,c.intentId(),UUID.randomUUID());var r=sevenReview(scenario);assertThat(r.totals().stoppedIntents()).isEqualTo(1);assertThat(r.totals().sourcesWithFreshMatches()).isEqualTo(6);assertThat(reviewedSource(r,Participant.PAYMENT).reasons()).contains(CommerceRetentionHoldRestorationReview.Reason.STOPPED);assertThat(r.executionTerminated()).isFalse();}
    }
    @Test void restorationReviewRootQuarantineKeepsAllSevenHistoriesBlocked()throws Exception{
        try(var http=http()){var scenario=sevenRestorations(http,false);jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE WHERE request_id=?",scenario.intake().requestId());var r=sevenReview(scenario);assertThat(r.code()).isEqualTo(CommerceRetentionHoldRestorationReview.Code.QUARANTINED);assertThat(r.sources()).hasSize(7);assertThat(r.totals().historicalAcknowledgements()).isEqualTo(7);assertThat(r.totals().sourcesWithFreshMatches()).isZero();for(var s:r.sources())assertThat(s.intents().getFirst().progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.BLOCKED);}
    }
    @Test void restorationReviewDuringInspectionSeesPendingWithoutWaitingForNetwork()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){var scenario=sevenRestorations(http,false);var c=scenario.commands().get(Participant.PAYMENT);var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var future=pool.submit(()->restorationProgress(restorationProgress(),c,UUID.randomUUID(),x->{var reply=http.inspectHold(x);holdResponse(arrived,release);return reply;}));try{assertThat(arrived.await(6,TimeUnit.SECONDS)).isTrue();var r=sevenReview(scenario);assertThat(r.totals().pendingReads()).isEqualTo(1);assertThat(r.totals().probes()).isEqualTo(15+scenario.additionalProbes());assertThat(reviewedSource(r,Participant.PAYMENT).reasons()).contains(CommerceRetentionHoldRestorationReview.Reason.READ_PENDING,CommerceRetentionHoldRestorationReview.Reason.INSPECTION_PENDING);}finally{release.countDown();}assertThat(future.get(6,TimeUnit.SECONDS).progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.MATCH_AS_OF_READ);assertThat(sevenReview(scenario).totals().pendingReads()).isZero();}
    }

    static void awaitReviewClock(UUID intent){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp()>GREATEST(i.created_at,COALESCE((SELECT max(created_at) FROM commerce_retention_hold_restoration_attempts WHERE intent_id=i.intent_id),'epoch'::timestamptz),COALESCE((SELECT max(r.recorded_at) FROM commerce_retention_hold_restoration_results r JOIN commerce_retention_hold_restoration_attempts a USING(attempt_id) WHERE a.intent_id=i.intent_id),'epoch'::timestamptz))+interval '20 milliseconds' FROM commerce_retention_hold_repair_intents i WHERE i.intent_id=?",Boolean.class,intent))){
            assertThat(System.nanoTime()).as("Fixture database clock must pass prior repair metadata").isLessThan(deadline);
            try{Thread.sleep(2);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        }
    }
    CommerceRetentionHoldRestorationProgress.Refresh restorationProgress(CommerceRetentionHoldRestorationProgress progress,SourceHoldRestorationProtocol.Command c,UUID probe,CommerceRetentionHoldReconciliation.Transport transport){awaitReviewClock(c.intentId());UUID request=jdbc.queryForObject("SELECT request_id FROM commerce_retention_hold_repair_intents WHERE intent_id=?",UUID.class,c.intentId());return progress.refresh(10,request,c.caseReference(),c.delivery().source(),c.intentId(),probe,transport);}
    CommerceRetentionHoldRestorationProgress restorationProgress(){return new CommerceRetentionHoldRestorationProgress(ds,manager);}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void restorationProgressFreshHttpMatchKeepsHistoryAndNeverGrantsClearance(Participant source)throws Exception{
        try(var http=http()){var c=restoration(http,source);rootRestore(rootRestoration(),c,http::restoreHold);var r=fixtureFreshProgress(c,http);UUID probe=r.probe().probeId();var p=r.progress();assertThat(p.code()).isEqualTo(CommerceRetentionHoldRestorationProgress.Code.ASSESSED);assertThat(p.delivery()).isEqualTo(CommerceRetentionHoldRestorationProgress.Delivery.HISTORICAL_ACKNOWLEDGEMENT);assertThat(p.inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.MATCH_AS_OF_READ);assertThat(p.pendingAttempts()).isZero();assertThat(p.currentAuthority()).isFalse();assertThat(p.activationAllowed()).isFalse();assertThat(p.repairExecutionAllowed()).isFalse();assertThat(restorationProgress(restorationProgress(),c,probe,x->{throw new AssertionError();}).progress().latestProbeId()).isEqualTo(probe);}
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void restorationProgressMatchAfterLostWriteReplyDoesNotAcknowledgePending(Participant source)throws Exception{
        try(var http=http()){var c=restoration(http,source);http.loseRestorationReply=source;rootRestore(rootRestoration(),c,http::restoreHold);http.loseRestorationReply=null;var p=fixtureFreshProgress(c,http).progress();assertThat(p.inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.MATCH_AS_OF_READ);assertThat(p.delivery()).isEqualTo(CommerceRetentionHoldRestorationProgress.Delivery.OUTCOME_UNKNOWN);assertThat(p.pendingAttempts()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_results",Integer.class)).isZero();assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Integer.class)).isEqualTo(1);}
    }
    @ParameterizedTest @ValueSource(strings={"stop","quarantine"})
    void restorationProgressLateContextBlocksMatchWithoutRewritingHistory(String change)throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);rootRestore(rootRestoration(),c,http::restoreHold);UUID request=jdbc.queryForObject("SELECT request_id FROM commerce_retention_hold_repair_intents WHERE intent_id=?",UUID.class,c.intentId());var r=restorationProgress(restorationProgress(),c,UUID.randomUUID(),x->{var reply=http.inspectHold(x);if(change.equals("stop"))rootRestoration().stop(10,request,c.caseReference(),Participant.PAYMENT,c.intentId(),UUID.randomUUID());else jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");return reply;});assertThat(r.progress().code()).isEqualTo(change.equals("stop")?CommerceRetentionHoldRestorationProgress.Code.STOPPED:CommerceRetentionHoldRestorationProgress.Code.QUARANTINED);assertThat(r.progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.BLOCKED);assertThat(r.progress().historicalAcknowledgements()).isEqualTo(1);assertThat(r.progress().executionTerminated()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_results",Integer.class)).isEqualTo(1);}
    }
    @Test void restorationProgressReboundSourceRequiresReviewEvenWhenUuidMatches()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);rootRestore(rootRestoration(),c,http::restoreHold);sourceSql(Participant.PAYMENT).update("UPDATE commerce_retention_source_state SET binding_epoch=binding_epoch+1 WHERE order_id=10");assertThat(restorationProgress(restorationProgress(),c,UUID.randomUUID(),http::inspectHold).progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.VERSION_CHANGED);}
    }
    @Test void restorationProgressMissingAfterHistoricalAckNeverRepairsAutomatically()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);rootRestore(rootRestoration(),c,http::restoreHold);sourceSql(Participant.PAYMENT).execute("TRUNCATE commerce_retention_source_holds CASCADE");var p=restorationProgress(restorationProgress(),c,UUID.randomUUID(),http::inspectHold).progress();assertThat(p.delivery()).isEqualTo(CommerceRetentionHoldRestorationProgress.Delivery.HISTORICAL_ACKNOWLEDGEMENT);assertThat(p.inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.MISSING_AT_SOURCE);assertThat(p.repairExecutionAllowed()).isFalse();assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Integer.class)).isZero();}
    }
    @Test void restorationProgressOldChallengeReplyCannotSupplyFreshMatch()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);rootRestore(rootRestoration(),c,http::restoreHold);var saved=new java.util.concurrent.atomic.AtomicReference<SourceHoldInspectionProtocol.Reply>();restorationProgress(restorationProgress(),c,UUID.randomUUID(),x->{var reply=http.inspectHold(x);saved.set(reply);return reply;});assertThat(restorationProgress(restorationProgress(),c,UUID.randomUUID(),x->saved.get()).progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.UNAVAILABLE);}
    }
    @Test void restorationProgressConcurrentSameProbeDoesNotSendSecondRpc()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){var c=restoration(http,Participant.PAYMENT);rootRestore(rootRestoration(),c,http::restoreHold);UUID probe=UUID.randomUUID();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var first=pool.submit(()->restorationProgress(restorationProgress(),c,probe,x->{var reply=http.inspectHold(x);holdResponse(arrived,release);return reply;}));try{assertThat(arrived.await(6,TimeUnit.SECONDS)).isTrue();var second=restorationProgress(restorationProgress(),c,probe,x->{throw new AssertionError();});assertThat(second.probe().code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.PENDING);assertThat(second.progress().pendingProbes()).isEqualTo(1);}finally{release.countDown();}assertThat(first.get(6,TimeUnit.SECONDS).progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.MATCH_AS_OF_READ);}
    }
    @Test void restorationProgressUnknownProbeCommitDoesNotInspectOrResend()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);rootRestore(rootRestoration(),c,http::restoreHold);UUID probe=UUID.randomUUID();var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var uncertain=deliveryCommitLoss("INSERT INTO commerce_retention_hold_probes(",lose);var progress=new CommerceRetentionHoldRestorationProgress(uncertain,new DataSourceTransactionManager(uncertain));var r=restorationProgress(progress,c,probe,x->{throw new AssertionError();});assertThat(lose.get()).isFalse();assertThat(r.probe().code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.UNAVAILABLE);assertThat(r.progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.PENDING);assertThat(restorationProgress(restorationProgress(),c,probe,x->{throw new AssertionError();}).probe().code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.PENDING);}
    }
    @Test void restorationProgressUnknownResultCommitReadsCommittedObservationWithoutRpc()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);rootRestore(rootRestoration(),c,http::restoreHold);UUID probe=UUID.randomUUID();var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var uncertain=deliveryCommitLoss("INSERT INTO commerce_retention_hold_probe_results(",lose);var r=restorationProgress(new CommerceRetentionHoldRestorationProgress(uncertain,new DataSourceTransactionManager(uncertain)),c,probe,http::inspectHold);assertThat(lose.get()).isFalse();assertThat(r.probe().code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.UNAVAILABLE);assertThat(r.progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.MATCH_AS_OF_READ);assertThat(restorationProgress(restorationProgress(),c,probe,x->{throw new AssertionError();}).probe().code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.RECORDED);}
    }
    @Test void restorationProgressObservationStartedBeforeReplayAckRequiresNewRead()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);http.loseRestorationReply=Participant.PAYMENT;rootRestore(rootRestoration(),c,http::restoreHold);http.loseRestorationReply=null;var p=restorationProgress(restorationProgress(),c,UUID.randomUUID(),x->{var reply=http.inspectHold(x);assertThat(rootRestore(rootRestoration(),RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),http::restoreHold).outcome()).isEqualTo("REPLAYED");return reply;}).progress();assertThat(p.delivery()).isEqualTo(CommerceRetentionHoldRestorationProgress.Delivery.HISTORICAL_ACKNOWLEDGEMENT);assertThat(p.pendingAttempts()).isEqualTo(1);assertThat(p.inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.OLDER_THAN_REPAIR);assertThat(restorationProgress(restorationProgress(),c,UUID.randomUUID(),http::inspectHold).progress().inspection()).isEqualTo(CommerceRetentionHoldRestorationProgress.Inspection.MATCH_AS_OF_READ);}
    }
    CommerceRetentionHoldRestorationDispatch rootRestoration(){return new CommerceRetentionHoldRestorationDispatch(ds,manager,RestorationCaseFixture.verifier());}
    CommerceRetentionHoldRestorationDispatch.Result rootRestore(CommerceRetentionHoldRestorationDispatch root,SourceHoldRestorationProtocol.Command c,CommerceRetentionHoldRestorationDispatch.Transport transport){
        UUID request=jdbc.queryForObject("SELECT request_id FROM commerce_retention_hold_repair_intents WHERE intent_id=?",UUID.class,c.intentId());return root.step(10,request,c.caseReference(),c.delivery().source(),c.intentId(),c.challenge(),c.mode(),c.authorization(),transport);
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void rootRestorationDurableAttemptAndReceiptAcrossActualHttp(Participant source)throws Exception{
        try(var http=http()){var c=restoration(http,source);var root=rootRestoration();var r=rootRestore(root,c,x->{outsideSql();return http.restoreHold(x);});assertThat(r.code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.RECORDED);assertThat(r.outcome()).isEqualTo("RESTORED");assertThat(rootRestore(root,c,x->{throw new AssertionError();})).isEqualTo(r);assertThat(jdbc.queryForObject("SELECT case_authorization_id FROM commerce_retention_hold_restoration_attempts",UUID.class)).isEqualTo(RestorationCaseFixture.verifier().verify(c).id());assertThat(sourceSql(source).queryForObject("SELECT hold_id FROM commerce_retention_source_holds",UUID.class)).isEqualTo(c.holdId());assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_results",Integer.class)).isEqualTo(1);}
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void rootRestorationLostSourceReplyRecoversOnlyByExplicitReplay(Participant source)throws Exception{
        try(var http=http()){var c=restoration(http,source);var root=rootRestoration();http.loseRestorationReply=source;assertThat(rootRestore(root,c,http::restoreHold).code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.PENDING);assertThat(rootRestore(root,c,x->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.PENDING);http.loseRestorationReply=null;long revision=sourceSql(source).queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class);var recovery=RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY);assertThat(rootRestore(root,recovery,http::restoreHold).outcome()).isEqualTo("REPLAYED");assertThat(sourceSql(source).queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(revision);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_attempts",Integer.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_results",Integer.class)).isEqualTo(1);}
    }
    @Test void rootRestorationLostAttemptCommitSendsNoRpcAndDoesNotResendOnRetry()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var uncertain=deliveryCommitLoss("INSERT INTO commerce_retention_hold_restoration_attempts",lose);var root=new CommerceRetentionHoldRestorationDispatch(uncertain,new DataSourceTransactionManager(uncertain),RestorationCaseFixture.verifier());assertThat(rootRestore(root,c,x->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.UNAVAILABLE);assertThat(lose.get()).isFalse();assertThat(rootRestore(rootRestoration(),c,x->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.PENDING);assertThat(rootRestore(rootRestoration(),RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),http::restoreHold).outcome()).isEqualTo("REPLAY_UNAVAILABLE");assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Integer.class)).isZero();}
    }
    @Test void rootRestorationLostResultCommitReadsHistoryWithoutRpc()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var uncertain=deliveryCommitLoss("INSERT INTO commerce_retention_hold_restoration_results",lose);var root=new CommerceRetentionHoldRestorationDispatch(uncertain,new DataSourceTransactionManager(uncertain),RestorationCaseFixture.verifier());assertThat(rootRestore(root,c,http::restoreHold).code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.UNAVAILABLE);assertThat(lose.get()).isFalse();assertThat(rootRestore(rootRestoration(),c,x->{throw new AssertionError();}).outcome()).isEqualTo("RESTORED");}
    }
    @Test void rootRestorationResultRollbackKeepsSourceReceiptAndPendingUntilReplay()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);jdbc.execute("CREATE FUNCTION fixture_root_restore_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-sensitive'; END; $$");jdbc.execute("CREATE TRIGGER fixture_root_restore_fail BEFORE INSERT ON commerce_retention_hold_restoration_results FOR EACH ROW EXECUTE FUNCTION fixture_root_restore_fail()");try{assertThat(rootRestore(rootRestoration(),c,http::restoreHold).code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.UNAVAILABLE);}finally{jdbc.execute("DROP TRIGGER fixture_root_restore_fail ON commerce_retention_hold_restoration_results");jdbc.execute("DROP FUNCTION fixture_root_restore_fail()");}assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Integer.class)).isEqualTo(1);assertThat(rootRestore(rootRestoration(),c,x->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldRestorationDispatch.Code.PENDING);assertThat(rootRestore(rootRestoration(),RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),http::restoreHold).outcome()).isEqualTo("REPLAYED");}
    }
    @ParameterizedTest @ValueSource(strings={"stop","quarantine"})
    void rootRestorationContextChangeAfterSourceCommitDiscardsPositiveReply(String change)throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);var root=rootRestoration();UUID request=jdbc.queryForObject("SELECT request_id FROM commerce_retention_hold_repair_intents WHERE intent_id=?",UUID.class,c.intentId());var r=rootRestore(root,c,x->{var reply=http.restoreHold(x);if(change.equals("stop"))root.stop(10,request,c.caseReference(),Participant.PAYMENT,c.intentId(),UUID.randomUUID());else jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE WHERE request_id=?",request);return reply;});assertThat(r.outcome()).isEqualTo(change.equals("stop")?"ROOT_STOPPED":"ROOT_QUARANTINED");assertThat(r.executionTerminated()).isFalse();assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Integer.class)).isEqualTo(1);}
    }
    @Test void rootRestorationExpiredMatchingPermitCanRecoverCommittedHistory()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);http.loseRestorationReply=Participant.PAYMENT;rootRestore(rootRestoration(),c,http::restoreHold);http.loseRestorationReply=null;var expired=RestorationCaseFixture.authorize(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),RestorationCaseFixture.verifier().verify(c).id(),Instant.now().minusSeconds(60));assertThat(rootRestore(rootRestoration(),expired,http::restoreHold).outcome()).isEqualTo("REPLAYED");}
    }
    SourceHoldRestorationProtocol.Command restoration(RetentionIntentHttpFixture http,Participant source)throws Exception{
        var r=deliveryIntake();deliver(r,source,http::deliverHold);sourceSql(source).execute("TRUNCATE commerce_retention_source_holds CASCADE");var history=new CommerceRetentionHoldReconciliationHistory(ds,manager);history.observe(10,r.requestId(),r.caseReference(),source,UUID.randomUUID(),http::inspectHold);UUID intent=UUID.randomUUID();assertThat(new CommerceRetentionHoldRecoveryPlanning(ds,manager).prepare(10,r.requestId(),r.caseReference(),source,intent).code()).isEqualTo(CommerceRetentionHoldRecoveryPlanning.Code.PREPARED);
        var prepared=new CommerceRetentionHoldRestorationPreparation(ds,manager).prepare(10,r.requestId(),r.caseReference(),source,intent,UUID.randomUUID(),"",SourceHoldRestorationProtocol.Mode.RESTORE);assertThat(prepared.code()).isEqualTo(CommerceRetentionHoldRestorationPreparation.Code.COMMAND_PREPARED);return RestorationCaseFixture.authorize(prepared.command());
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceRestorationAtomicallyRecreatesOriginalUuidAndReplaysWithoutAnotherRevision(Participant source)throws Exception{
        try(var http=http()){
            var c=restoration(http,source);var q=sourceSql(source);var restored=http.restoreHold(c);assertThat(restored.code()).isEqualTo(SourceHoldRestorationProtocol.Code.RESTORED);assertThat(restored.sourceRevision()).isGreaterThan(c.sourceRevision());assertThat(q.queryForObject("SELECT hold_id FROM commerce_retention_source_holds",UUID.class)).isEqualTo(c.holdId());assertThat(q.queryForObject("SELECT request_id FROM commerce_retention_source_holds",UUID.class)).isEqualTo(c.delivery().requestId());assertThat(q.queryForObject("SELECT invalidated FROM commerce_retention_source_state WHERE order_id=10",Boolean.class)).isTrue();
            var again=http.restoreHold(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY));assertThat(again.code()).isEqualTo(SourceHoldRestorationProtocol.Code.REPLAYED);assertThat(again.sourceRevision()).isEqualTo(restored.sourceRevision());assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(restored.sourceRevision());assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Long.class)).isEqualTo(1);assertThat(q.queryForObject("SELECT command_hash FROM commerce_retention_hold_restoration_receipts",String.class)).isEqualTo(SourceHoldRestorationProtocol.scopeHash(c));assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_change_outbox WHERE order_id=10 AND state IN ('PENDING','FAILED')",Long.class)).isPositive();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceRestorationRequiresCryptographicCaseAuthorizationForEverySource(Participant source)throws Exception{
        try(var http=http()){
            var c=restoration(http,source);var bad=new SourceHoldRestorationProtocol.Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),c.bindingEpoch(),c.sourceRevision(),UUID.randomUUID(),"true",c.mode());assertThat(http.restoreHold(bad).code()).isEqualTo(SourceHoldRestorationProtocol.Code.AUTHORIZATION_REQUIRED);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Long.class)).isZero();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceRestorationRejectsChangedSourceRevisionWithoutPartialWrite(Participant source)throws Exception{
        try(var http=http()){
            var c=restoration(http,source);UUID other=holdRegistry(source).place(10,UUID.randomUUID(),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionSourceHolds.Reason.OPEN_CASE);assertThat(http.restoreHold(c).code()).isEqualTo(SourceHoldRestorationProtocol.Code.STALE_SOURCE);assertThat(sourceSql(source).queryForObject("SELECT hold_id FROM commerce_retention_source_holds",UUID.class)).isEqualTo(other);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Long.class)).isZero();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceRestorationQuarantineBlocksNewWriteAndHistoricalReplay(Participant source)throws Exception{
        try(var http=http()){
            var c=restoration(http,source);http.restoreHold(c);sourceSql(source).update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE WHERE order_id=10");assertThat(http.restoreHold(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY)).code()).isEqualTo(SourceHoldRestorationProtocol.Code.QUARANTINED);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceRestorationExpiredProofAndReplayOnlyCannotCreateHold(Participant source)throws Exception{
        try(var http=http()){
            var c=restoration(http,source);var expired=RestorationCaseFixture.authorize(c,UUID.randomUUID(),Instant.now().minusSeconds(60));assertThat(http.restoreHold(expired).code()).isEqualTo(SourceHoldRestorationProtocol.Code.EXPIRED_AUTHORIZATION);assertThat(http.restoreHold(RestorationCaseFixture.retry(expired,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY)).code()).isEqualTo(SourceHoldRestorationProtocol.Code.REPLAY_UNAVAILABLE);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceRestorationConflictingHoldIdentityCannotBeOverwritten(Participant source)throws Exception{
        try(var http=http()){
            var c=restoration(http,source);UUID other=holdRegistry(source).place(10,c.delivery().requestId(),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionSourceHolds.Reason.OPEN_CASE);long revision=sourceSql(source).queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class);var changed=new SourceHoldRestorationProtocol.Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),c.bindingEpoch(),revision,c.challenge(),"",c.mode());assertThat(http.restoreHold(RestorationCaseFixture.authorize(changed)).code()).isEqualTo(SourceHoldRestorationProtocol.Code.CONFLICT);assertThat(sourceSql(source).queryForObject("SELECT hold_id FROM commerce_retention_source_holds",UUID.class)).isEqualTo(other);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceRestorationReplyLossAfterCommitUsesDurableReceiptWithoutDuplicateHold(Participant source)throws Exception{
        try(var http=http()){
            var c=restoration(http,source);http.loseRestorationReply=source;assertThatThrownBy(()->http.restoreHold(c)).hasMessage("SOURCE_HOLD_RESTORATION_UNAVAILABLE").hasNoCause();long revision=sourceSql(source).queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class);http.loseRestorationReply=null;assertThat(http.restoreHold(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY)).code()).isEqualTo(SourceHoldRestorationProtocol.Code.REPLAYED);assertThat(sourceSql(source).queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(revision);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
        }
    }
    @Test void sourceRestorationReceiptFailureRollsBackHoldRevisionAndOutbox()throws Exception{
        try(var http=http()){
            var c=restoration(http,Participant.PAYMENT);var q=sourceSql(Participant.PAYMENT);long before=q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class);q.execute("CREATE FUNCTION fixture_restore_receipt_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only'; END; $$");q.execute("CREATE TRIGGER fixture_restore_receipt_failure BEFORE INSERT ON commerce_retention_hold_restoration_receipts FOR EACH ROW EXECUTE FUNCTION fixture_restore_receipt_failure()");
            try{assertThatThrownBy(()->http.restoreHold(c)).hasMessage("SOURCE_HOLD_RESTORATION_UNAVAILABLE");}finally{q.execute("DROP TRIGGER fixture_restore_receipt_failure ON commerce_retention_hold_restoration_receipts");q.execute("DROP FUNCTION fixture_restore_receipt_failure()");}
            assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(before);assertThat(http.restoreHold(c).code()).isEqualTo(SourceHoldRestorationProtocol.Code.RESTORED);
        }
    }
    @Test void sourceRestorationConcurrentRetriesHaveOneHoldReceiptAndRevisionAdvance()throws Exception{
        try(var http=http();var pool=Executors.newFixedThreadPool(2)){
            var c=restoration(http,Participant.PAYMENT);var retry=RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.RESTORE);var a=pool.submit(()->http.restoreHold(c));var b=pool.submit(()->http.restoreHold(retry));var first=a.get(6,TimeUnit.SECONDS);var second=b.get(6,TimeUnit.SECONDS);assertThat(List.of(first.code(),second.code())).containsExactlyInAnyOrder(SourceHoldRestorationProtocol.Code.RESTORED,SourceHoldRestorationProtocol.Code.REPLAYED);assertThat(first.sourceRevision()).isEqualTo(second.sourceRevision());assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Long.class)).isEqualTo(1);
        }
    }
    @Test void sourceRestorationHttpEnforcesRolesMethodBoundsAndOwnSource()throws Exception{
        try(var http=http()){
            var c=restoration(http,Participant.PAYMENT);byte[] raw=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(c);
            for(String token:new String[]{null,"customer","operator","other","unknown"})assertThat(http.restorationRequest(Participant.PAYMENT,token,"POST",raw).statusCode()).isIn(401,403);assertThat(http.restorationRequest(Participant.PAYMENT,"order","GET",raw).statusCode()).isEqualTo(403);assertThat(http.restorationRequest(Participant.PAYMENT,"order","POST",new byte[SourceHoldRestorationProtocol.REQUEST_LIMIT+1]).statusCode()).isEqualTo(413);assertThat(http.restorationRequest(Participant.INVENTORY,"order","POST",raw).statusCode()).isEqualTo(400);
        }
    }
    @Test void sourceRestorationCallerTransactionIsRejectedBeforeAnySql()throws Exception{
        try(var http=http()){
            var c=restoration(http,Participant.PAYMENT);var m=sourceManager(Participant.PAYMENT);var restorer=new SourceHoldRestorer(Participant.PAYMENT,m.getDataSource(),m,RestorationCaseFixture.verifier());new TransactionTemplate(m).executeWithoutResult(tx->assertThatThrownBy(()->restorer.restore(c)).hasMessage("SOURCE_HOLD_RESTORATION_CALLER_TRANSACTION"));assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
        }
    }
    @Test void sourceRestorationChangedEpochCannotPassCas()throws Exception{
        try(var http=http()){var c=restoration(http,Participant.PAYMENT);sourceSql(Participant.PAYMENT).update("UPDATE commerce_retention_source_state SET binding_epoch=binding_epoch+1 WHERE order_id=10");assertThat(http.restoreHold(c).code()).isEqualTo(SourceHoldRestorationProtocol.Code.STALE_SOURCE);assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();}
    }
    @Test void sourceRestorationLostDatabaseCommitAcknowledgementIsRecoveredByReceipt()throws Exception{
        try(var http=http()){
            var c=restoration(http,Participant.PAYMENT);var base=sourceManager(Participant.PAYMENT).getDataSource();var uncertain=deliveryCommitLoss(base,"INSERT INTO commerce_retention_hold_restoration_receipts(",new java.util.concurrent.atomic.AtomicBoolean(true));var restorer=new SourceHoldRestorer(Participant.PAYMENT,uncertain,new DataSourceTransactionManager(uncertain),RestorationCaseFixture.verifier());
            assertThatThrownBy(()->restorer.restore(c)).isInstanceOf(org.springframework.transaction.TransactionException.class);assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Long.class)).isEqualTo(1);assertThat(http.restoreHold(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY)).code()).isEqualTo(SourceHoldRestorationProtocol.Code.REPLAYED);
        }
    }
    @Test void sourceRestorationExpiredMatchingAuthorizationCanReadCommittedHistoryOnly()throws Exception{
        try(var http=http()){
            var c=restoration(http,Participant.PAYMENT);var first=http.restoreHold(c);var expired=RestorationCaseFixture.authorize(RestorationCaseFixture.retry(c,SourceHoldRestorationProtocol.Mode.REPLAY_ONLY),first.caseAuthorizationId(),Instant.now().minusSeconds(60));assertThat(http.restoreHold(expired).code()).isEqualTo(SourceHoldRestorationProtocol.Code.REPLAYED);assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(first.sourceRevision());
        }
    }
    @ParameterizedTest @ValueSource(strings={"case","authorization"})
    void sourceRestorationExistingIntentCannotRebindCaseOrAuthorization(String variant)throws Exception{
        try(var http=http()){
            var c=restoration(http,Participant.PAYMENT);http.restoreHold(c);var changed=new SourceHoldRestorationProtocol.Command(c.delivery(),c.intentId(),variant.equals("case")?UUID.randomUUID():c.caseReference(),c.holdId(),c.bindingEpoch(),c.sourceRevision(),UUID.randomUUID(),"",SourceHoldRestorationProtocol.Mode.RESTORE);assertThat(http.restoreHold(RestorationCaseFixture.authorize(changed)).code()).isEqualTo(SourceHoldRestorationProtocol.Code.CONFLICT);assertThat(sourceSql(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_hold_restoration_receipts",Long.class)).isEqualTo(1);
        }
    }
    @Test void sourceRestorationReceiptCannotBeRewrittenOrDeleted()throws Exception{
        try(var http=http()){
            var c=restoration(http,Participant.PAYMENT);http.restoreHold(c);var q=sourceSql(Participant.PAYMENT);for(String sql:List.of("DELETE FROM commerce_retention_hold_restoration_receipts","UPDATE commerce_retention_hold_restoration_receipts SET command_hash=repeat('b',64)"))assertThatThrownBy(()->q.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdRecoveryPlanningAccountsRealReadOnlyProbeAndPreparesUnexecutedIntent(Participant source)throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            var r=deliveryIntake();UUID expected=deliver(r,source,http::deliverHold).sourceHoldId();var history=new CommerceRetentionHoldReconciliationHistory(ds,manager);var planning=new CommerceRetentionHoldRecoveryPlanning(ds,manager);UUID old=UUID.randomUUID(),replacement=UUID.randomUUID(),intent=UUID.randomUUID();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            var future=pool.submit(()->history.observe(10,r.requestId(),r.caseReference(),source,old,c->{var reply=http.inspectHold(c);holdResponse(arrived,release);return reply;}));
            try{
                assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();sourceSql(source).execute("TRUNCATE commerce_retention_source_holds CASCADE");assertThat(history.observe(10,r.requestId(),r.caseReference(),source,replacement,http::inspectHold).outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE);
                assertThat(planning.prepare(10,r.requestId(),r.caseReference(),source,intent).code()).isEqualTo(CommerceRetentionHoldRecoveryPlanning.Code.NOT_CANDIDATE);
                var resolution=planning.supersede(10,r.requestId(),r.caseReference(),source,old,replacement,UUID.randomUUID());assertThat(resolution.code()).isEqualTo(CommerceRetentionHoldRecoveryPlanning.Code.RECORDED);assertThat(resolution.executionTerminated()).isFalse();
                var prepared=planning.prepare(10,r.requestId(),r.caseReference(),source,intent);assertThat(prepared.code()).isEqualTo(CommerceRetentionHoldRecoveryPlanning.Code.PREPARED);assertThat(prepared.repairAllowed()).isFalse();assertThat(prepared.activationAllowed()).isFalse();var stored=planning.inspect(10,r.requestId(),r.caseReference(),source).intents().getFirst();assertThat(stored.expectedHoldId()).isEqualTo(expected);assertThat(stored.sourceRequestId()).isEqualTo(r.sourceRequests().get(source));
            }finally{release.countDown();}
            assertThat(future.get(5,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.SUPERSEDED);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probe_results",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdRepairAssessmentUsesActualMissingSourceObservationWithoutAuthorizingRepair(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();UUID expected=deliver(r,source,http::deliverHold).sourceHoldId();sourceSql(source).execute("TRUNCATE commerce_retention_source_holds CASCADE");UUID probe=UUID.randomUUID();
            var history=new CommerceRetentionHoldReconciliationHistory(ds,manager);assertThat(history.observe(10,r.requestId(),r.caseReference(),source,probe,http::inspectHold).code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.RECORDED);
            long calls=http.calls.size();var a=new CommerceRetentionHoldRepairAssessment(ds,manager).assess(10,r.requestId(),r.caseReference());var s=a.sources().stream().filter(v->v.source()==source).findFirst().orElseThrow();
            assertThat(s.state()).isEqualTo(CommerceRetentionHoldRepairAssessment.State.MISSING_HOLD_CANDIDATE);assertThat(s.candidate().sourceRequestId()).isEqualTo(r.sourceRequests().get(source));assertThat(s.candidate().expectedHoldId()).isEqualTo(expected);assertThat(s.candidate().basisProbeId()).isEqualTo(probe);assertThat(s.candidate().executionAllowed()).isFalse();assertThat(a.repairAllowed()).isFalse();assertThat(a.activationAllowed()).isFalse();assertThat(http.calls).hasSize((int)calls);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_probes",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdProbeHistoryRecordsRealSourceReplyAndIdempotentRetryUsesNoRpc(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,source,http::deliverHold);var history=new CommerceRetentionHoldReconciliationHistory(ds,manager);UUID probe=UUID.randomUUID();
            var first=history.observe(10,r.requestId(),r.caseReference(),source,probe,http::inspectHold);assertThat(first.code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.RECORDED);assertThat(first.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);
            assertThat(history.observe(10,r.requestId(),r.caseReference(),source,probe,c->{throw new AssertionError();})).isEqualTo(first);assertThat(history.inspect(10,r.requestId(),r.caseReference(),source).entries()).hasSize(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdProbeHistoryRecordsActualDiscrepancyWithoutRepairingSource(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,source,http::deliverHold);sourceSql(source).execute("TRUNCATE commerce_retention_source_holds CASCADE");var history=new CommerceRetentionHoldReconciliationHistory(ds,manager);
            var result=history.observe(10,r.requestId(),r.caseReference(),source,UUID.randomUUID(),http::inspectHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.RECORDED);assertThat(result.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE);assertThat(history.inspect(10,r.requestId(),r.caseReference(),source).entries().getFirst().requiresReview()).isTrue();assertThat(result.repairPerformed()).isFalse();assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
        }
    }
    @Test void holdProbeHistoryPersistsNegativeOutcomeWhenRootIsQuarantinedDuringRealRpc()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,Participant.PAYMENT,http::deliverHold);var history=new CommerceRetentionHoldReconciliationHistory(ds,manager);http.afterDispatch=c->{if(c.path().equals(SourceHoldInspectionProtocol.PATH))jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");};
            var result=history.observe(10,r.requestId(),r.caseReference(),Participant.PAYMENT,UUID.randomUUID(),http::inspectHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.RECORDED);assertThat(result.outcome().code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED);assertThat(result.outcome().observedAt()).isNull();assertThat(history.inspect(10,r.requestId(),r.caseReference(),Participant.PAYMENT).code()).isEqualTo(CommerceRetentionHoldReconciliationHistory.Code.QUARANTINED);
        }
    }
    CommerceRetentionHoldReconciliation.Result reconcile(CommerceRetentionHoldIntake.Registration r,Participant source,CommerceRetentionHoldReconciliation.Transport transport){return new CommerceRetentionHoldReconciliation(ds,manager).inspect(10,r.requestId(),r.caseReference(),source,transport);}
    void noReconciliationAuthority(CommerceRetentionHoldReconciliation.Result result){assertThat(result.activationAllowed()).isFalse();assertThat(result.completeCaseCoverage()).isFalse();assertThat(result.repairPerformed()).isFalse();assertThat(result.persistentReconciliationRecorded()).isFalse();}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdReconciliationMatchesLiveSourceWithoutChangingHistory(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,source,http::deliverHold);var q=sourceSql(source);long revision=q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class);
            var result=reconcile(r,source,c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return http.inspectHold(c);});
            assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);assertThat(result.sourceRevision()).isEqualTo(revision);assertThat(result.bindingEpoch()).isPositive();assertThat(result.observedAt()).isNotNull();noReconciliationAuthority(result);
            assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state WHERE order_id=10",Long.class)).isEqualTo(revision);assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdReconciliationDetectsMissingLedgerBehindHistoricalAck(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();UUID original=deliver(r,source,http::deliverHold).sourceHoldId();sourceSql(source).execute("TRUNCATE commerce_retention_source_holds CASCADE"); // Simulate loss of this source ledger in the owned fixture only.
            var result=reconcile(r,source,http::inspectHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MISSING_AT_SOURCE);noReconciliationAuthority(result);
            assertThat(jdbc.queryForObject("SELECT source_hold_id FROM commerce_retention_hold_delivery_receipts",UUID.class)).isEqualTo(original);assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdReconciliationQuarantinedRestoredSourceNeverMatches(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,source,http::deliverHold);sourceSql(source).update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE WHERE order_id=10");
            var result=reconcile(r,source,http::inspectHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED);noReconciliationAuthority(result);assertThat(sourceSql(source).queryForObject("SELECT restore_blocked FROM commerce_retention_source_state WHERE order_id=10",Boolean.class)).isTrue();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdReconciliationMissingPurchaseIsNotAbsenceClearance(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,source,http::deliverHold);sourceSql(source).execute("TRUNCATE commerce_retention_source_state CASCADE");
            var result=reconcile(r,source,http::inspectHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.SOURCE_UNKNOWN);assertThat(result.bindingEpoch()).isZero();noReconciliationAuthority(result);
            assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_state",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdReconciliationConflictDoesNotOverwriteOrLeakForeignHold(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();UUID original=deliver(r,source,http::deliverHold).sourceHoldId();sourceSql(source).execute("TRUNCATE commerce_retention_source_holds CASCADE");UUID changed=holdRegistry(source).place(10,r.sourceRequests().get(source),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionSourceHolds.Reason.OPEN_CASE);
            var result=reconcile(r,source,http::inspectHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.SOURCE_CONFLICT);noReconciliationAuthority(result);assertThat(result.toString()).doesNotContain(original.toString(),changed.toString());
            assertThat(jdbc.queryForObject("SELECT source_hold_id FROM commerce_retention_hold_delivery_receipts",UUID.class)).isEqualTo(original);assertThat(sourceSql(source).queryForObject("SELECT hold_id FROM commerce_retention_source_holds",UUID.class)).isEqualTo(changed);
        }
    }
    @Test void holdReconciliationNoReceiptWrongScopeOrCallerTransactionSendsNoRpc(){
        var r=deliveryIntake();var adapter=new CommerceRetentionHoldReconciliation(ds,manager);CommerceRetentionHoldReconciliation.Transport forbidden=c->{throw new AssertionError("Unexpected inspection RPC");};
        assertThat(reconcile(r,Participant.PAYMENT,forbidden).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.NO_HISTORICAL_RECEIPT);
        assertThat(adapter.inspect(11,r.requestId(),r.caseReference(),Participant.PAYMENT,forbidden).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNKNOWN_REQUEST);assertThat(adapter.inspect(10,r.requestId(),UUID.randomUUID(),Participant.PAYMENT,forbidden).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNKNOWN_REQUEST);
        assertThat(adapter.inspect(10,UUID.randomUUID(),r.caseReference(),Participant.PAYMENT,forbidden).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNKNOWN_REQUEST);
        new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(()->reconcile(r,Participant.PAYMENT,forbidden)).hasMessage("RETENTION_HOLD_RECONCILIATION_CALLER_TRANSACTION"));
        jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");assertThat(reconcile(r,Participant.PAYMENT,forbidden).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"challenge","hold","source","order","guard","reason","stale","future","version"})
    void holdReconciliationMismatchedOrStaleReplyCannotMatch(String mismatch)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,Participant.PAYMENT,http::deliverHold);
            var result=reconcile(r,Participant.PAYMENT,c->{
                var actual=http.inspectHold(c);var d=c.delivery();var changed=new SourceHoldDeliveryProtocol.Command(mismatch.equals("source")?Participant.INVENTORY:d.source(),mismatch.equals("order")?11:d.orderId(),d.requestId(),mismatch.equals("guard")?Guard.NO_OPEN_OR_REOPENED_CASE:d.guard(),mismatch.equals("reason")?CommerceRetentionSourceHolds.Reason.OPEN_CASE:d.reason());
                var command=new SourceHoldInspectionProtocol.Command(changed,mismatch.equals("hold")?UUID.randomUUID():c.expectedHoldId(),mismatch.equals("challenge")?UUID.randomUUID():c.challenge());
                return new SourceHoldInspectionProtocol.Reply(command,actual.code(),mismatch.equals("stale")?Instant.now().minusSeconds(30):mismatch.equals("future")?Instant.now().plusSeconds(30):actual.observedAt(),mismatch.equals("version")?0:actual.bindingEpoch(),actual.sourceRevision());
            });assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNAVAILABLE);assertThat(result.observedAt()).isNull();noReconciliationAuthority(result);
        }
    }
    @Test void holdReconciliationRootQuarantineAfterReadDiscardsObservation()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            var r=deliveryIntake();deliver(r,Participant.PAYMENT,http::deliverHold);var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            http.afterDispatch=c->{if(c.path().equals(SourceHoldInspectionProtocol.PATH))holdResponse(arrived,release);};var future=pool.submit(()->reconcile(r,Participant.PAYMENT,http::inspectHold));
            try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE");release.countDown();var result=future.get(5,TimeUnit.SECONDS);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.ROOT_QUARANTINED);assertThat(result.observedAt()).isNull();noReconciliationAuthority(result);}finally{release.countDown();}
        }
    }
    @Test void holdReconciliationInspectionHttpEnforcesRolesMethodBoundsAndOwnSource()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();UUID hold=deliver(r,Participant.PAYMENT,http::deliverHold).sourceHoldId();var command=new SourceHoldInspectionProtocol.Command(new SourceHoldDeliveryProtocol.Command(Participant.PAYMENT,10,r.sourceRequests().get(Participant.PAYMENT),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST),hold,UUID.randomUUID());var json=tools.jackson.databind.json.JsonMapper.builder().build();byte[] body=json.writeValueAsBytes(command);
            for(String token:new String[]{null,"customer","operator","other","unknown"})assertThat(http.inspectionRequest(Participant.PAYMENT,token,"POST",body).statusCode()).isIn(401,403);
            assertThat(http.inspectionRequest(Participant.PAYMENT,"order","GET",body).statusCode()).isEqualTo(403);assertThat(http.inspectionRequest(Participant.PAYMENT,"order","POST",new byte[SourceHoldInspectionProtocol.REQUEST_LIMIT+1]).statusCode()).isEqualTo(413);
            assertThat(http.inspectionRequest(Participant.PAYMENT,"order","POST","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);assertThat(http.inspectionRequest(Participant.INVENTORY,"order","POST",body).statusCode()).isEqualTo(400);var valid=http.inspectionRequest(Participant.PAYMENT,"order","POST",body);assertThat(valid.statusCode()).isEqualTo(200);assertThat(valid.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));
        }
    }
    @Test void holdReconciliationSourceSqlFailureIsUnavailableAndNeverMissing()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,Participant.PAYMENT,http::deliverHold);var q=sourceSql(Participant.PAYMENT);q.execute("ALTER TABLE commerce_retention_source_holds RENAME TO fixture_unavailable_holds");
            try{var result=reconcile(r,Participant.PAYMENT,http::inspectHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNAVAILABLE);noReconciliationAuthority(result);}finally{q.execute("ALTER TABLE fixture_unavailable_holds RENAME TO commerce_retention_source_holds");}
        }
    }
    @Test void holdReconciliationNewChallengeRejectsCachedReplyFromPriorInspection()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,Participant.PAYMENT,http::deliverHold);var saved=new java.util.concurrent.atomic.AtomicReference<SourceHoldInspectionProtocol.Reply>();
            assertThat(reconcile(r,Participant.PAYMENT,c->{var reply=http.inspectHold(c);saved.set(reply);return reply;}).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);
            assertThat(reconcile(r,Participant.PAYMENT,c->saved.get()).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNAVAILABLE);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);
        }
    }
    @Test void holdReconciliationSourceRejectsOuterTransaction()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();UUID hold=deliver(r,Participant.PAYMENT,http::deliverHold).sourceHoldId();var m=sourceManager(Participant.PAYMENT);var inspector=new SourceHoldInspector(Participant.PAYMENT,m.getDataSource(),m);
            var c=new SourceHoldInspectionProtocol.Command(new SourceHoldDeliveryProtocol.Command(Participant.PAYMENT,10,r.sourceRequests().get(Participant.PAYMENT),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST),hold,UUID.randomUUID());
            new TransactionTemplate(m).executeWithoutResult(tx->assertThatThrownBy(()->inspector.inspect(c)).hasMessage("SOURCE_HOLD_INSPECTION_CALLER_TRANSACTION"));
        }
    }
    @Test void holdReconciliationActualTimeoutDoesNotInventMissingStateOrRepair()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            var r=deliveryIntake();deliver(r,Participant.PAYMENT,http::deliverHold);var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);http.afterDispatch=c->{if(c.path().equals(SourceHoldInspectionProtocol.PATH))holdResponse(arrived,release);};
            var future=pool.submit(()->reconcile(r,Participant.PAYMENT,http::inspectHold));
            try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();var result=future.get(8,TimeUnit.SECONDS);assertThat(result.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.UNAVAILABLE);noReconciliationAuthority(result);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);}finally{release.countDown();}
        }
    }
    @Test void holdReconciliationMatchIsAsOfReadAndLaterQuarantineRequiresNewProbe()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();deliver(r,Participant.PAYMENT,http::deliverHold);
            http.afterDispatch=c->{if(c.path().equals(SourceHoldInspectionProtocol.PATH))sourceSql(Participant.PAYMENT).update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE WHERE order_id=10");};
            var first=reconcile(r,Participant.PAYMENT,http::inspectHold);assertThat(first.code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.MATCH_AS_OF_READ);noReconciliationAuthority(first);
            http.afterDispatch=c->{};assertThat(reconcile(r,Participant.PAYMENT,http::inspectHold).code()).isEqualTo(CommerceRetentionHoldReconciliation.Code.SOURCE_QUARANTINED);
        }
    }
    CommerceRetentionHoldIntake.Registration deliveryIntake(){return new CommerceRetentionHoldIntake(ds,manager).register(10,UUID.randomUUID(),UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);}
    CommerceRetentionHoldDelivery.Result deliver(CommerceRetentionHoldIntake.Registration r,Participant p,CommerceRetentionHoldDelivery.Transport remote){return new CommerceRetentionHoldDelivery(ds,manager).deliver(10,r.requestId(),r.caseReference(),p,c->{outsideSql();return remote.deliver(c);});}
    long holdCalls(RetentionIntentHttpFixture http){return http.calls.stream().filter(c->c.path().equals(SourceHoldDeliveryProtocol.PATH)).count();}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdDeliveryPersistsExactSourceAcknowledgementAndSkipsAlreadyRecordedRpc(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();var result=deliver(r,source,http::deliverHold);assertThat(result.code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RECORDED);
            assertThat(result.activationAllowed()).isFalse();assertThat(result.completeCaseCoverage()).isFalse();
            assertThat(sourceSql(source).queryForObject("SELECT hold_id FROM commerce_retention_source_holds WHERE request_id=?",UUID.class,r.sourceRequests().get(source))).isEqualTo(result.sourceHoldId());
            assertThat(jdbc.queryForObject("SELECT source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id=? AND source=?",UUID.class,r.requestId(),source.name())).isEqualTo(result.sourceHoldId());
            assertThat(deliver(r,source,c->{throw new AssertionError("Acknowledged source must not receive another RPC");}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.ALREADY_RECORDED);
            assertThat(holdCalls(http)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdDeliveryReplyLossAfterSourceCommitRequiresExplicitIdempotentRetry(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();http.loseHoldReply=source;assertThat(deliver(r,source,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RETRY_REQUIRED);
            var id=sourceSql(source).queryForObject("SELECT hold_id FROM commerce_retention_source_holds",UUID.class);var revision=sourceSql(source).queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();assertThat(holdCalls(http)).isEqualTo(1);
            http.loseHoldReply=null;assertThat(deliver(r,source,http::deliverHold).sourceHoldId()).isEqualTo(id);assertThat(holdCalls(http)).isEqualTo(2);
            assertThat(sourceSql(source).queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class)).isEqualTo(revision);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(2);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdDeliveryUnknownPurchaseStaysPendingUntilExplicitSourceBinding(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();sourceSql(source).execute("TRUNCATE commerce_retention_source_state,commerce_retention_change_outbox RESTART IDENTITY CASCADE");
            assertThat(deliver(r,source,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RETRY_REQUIRED);
            assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();
            var m=sourceManager(source);new CommerceRetentionSourceOutbox(m.getDataSource(),m).bind(subject,Phase.REDACTION,0);
            assertThat(deliver(r,source,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RECORDED);
            assertThat(holdCalls(http)).isEqualTo(2);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdDeliverySourceQuarantineNeverProducesAcknowledgement(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();sourceSql(source).update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE");
            assertThat(deliver(r,source,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RETRY_REQUIRED);
            assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void holdDeliverySourceRequestConflictIsNotAnAcknowledgement(Participant source)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();holdRegistry(source).place(10,r.sourceRequests().get(source),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionSourceHolds.Reason.OPEN_CASE);
            assertThat(deliver(r,source,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RETRY_REQUIRED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
        }
    }
    @Test void holdDeliveryHttpRejectsForeignActorsWrongMethodOversizeAndForeignSource()throws Exception{
        try(var http=http()){
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            for(var source:CommerceRetentionSourceHolds.SOURCES){
                var c=new SourceHoldDeliveryProtocol.Command(source,10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);var raw=json.writeValueAsBytes(c);
                for(String token:List.of("customer","operator","other"))assertThat(http.holdRequest(source,token,"POST",raw).statusCode()).isEqualTo(403);
                assertThat(http.holdRequest(source,null,"POST",raw).statusCode()).isEqualTo(401);assertThat(http.holdRequest(source,"order","GET",raw).statusCode()).isEqualTo(403);
                assertThat(http.holdRequest(source,"order","POST",new byte[SourceHoldDeliveryProtocol.REQUEST_LIMIT+1]).statusCode()).isEqualTo(413);
                var foreign=new SourceHoldDeliveryProtocol.Command(source==Participant.PAYMENT?Participant.INVENTORY:Participant.PAYMENT,10,UUID.randomUUID(),c.guard(),c.reason());
                assertThat(http.holdRequest(source,"order","POST",json.writeValueAsBytes(foreign)).statusCode()).isEqualTo(400);
                assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"source","order","request","guard","reason","hold","negative"})
    void holdDeliveryMismatchedOrMalformedReplyCannotCreateReceipt(String mismatch)throws Exception{
        try(var http=http()){
            var r=deliveryIntake();var result=deliver(r,Participant.PAYMENT,c->{
                var actual=http.deliverHold(c);var changed=new SourceHoldDeliveryProtocol.Command(mismatch.equals("source")?Participant.INVENTORY:c.source(),mismatch.equals("order")?11:c.orderId(),mismatch.equals("request")?UUID.randomUUID():c.requestId(),mismatch.equals("guard")?Guard.NO_OPEN_OR_REOPENED_CASE:c.guard(),mismatch.equals("reason")?CommerceRetentionSourceHolds.Reason.OPEN_CASE:c.reason());
                return new SourceHoldDeliveryProtocol.Reply(changed,mismatch.equals("negative")?SourceHoldDeliveryProtocol.Code.UNKNOWN_PURCHASE:actual.code(),mismatch.equals("hold")?null:actual.holdId());
            });
            assertThat(result.code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RETRY_REQUIRED);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();
        }
    }
    @Test void holdDeliveryRootQuarantineWhileResponseIsInFlightDiscardsAcknowledgement()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            var r=deliveryIntake();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            http.afterDispatch=c->{if(c.path().equals(SourceHoldDeliveryProtocol.PATH))holdResponse(arrived,release);};
            var pending=pool.submit(()->deliver(r,Participant.INVENTORY,http::deliverHold));
            try{
                assertThat(arrived.await(8,TimeUnit.SECONDS)).isTrue();jdbc.update("UPDATE commerce_retention_hold_requests SET restore_blocked=TRUE WHERE request_id=?",r.requestId());release.countDown();
                assertThat(pending.get(8,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.QUARANTINED);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();assertThat(inventorySql.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
                assertThat(deliver(r,Participant.INVENTORY,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.QUARANTINED);
            }finally{release.countDown();}
        }
    }
    @Test void holdDeliveryUnknownScopeAndCallerTransactionSendNoRpc(){
        var r=deliveryIntake();var sender=new CommerceRetentionHoldDelivery(ds,manager);
        assertThat(sender.deliver(11,r.requestId(),r.caseReference(),Participant.PAYMENT,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.UNKNOWN_REQUEST);
        assertThat(sender.deliver(10,r.requestId(),UUID.randomUUID(),Participant.PAYMENT,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.UNKNOWN_REQUEST);
        new TransactionTemplate(manager).executeWithoutResult(tx->assertThatThrownBy(()->sender.deliver(10,r.requestId(),r.caseReference(),Participant.PAYMENT,c->{throw new AssertionError();})).hasMessage("RETENTION_HOLD_DELIVERY_CALLER_TRANSACTION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isZero();
    }
    @Test void holdDeliveryBudgetAndSqlHistoryCannotBeResetOrForged(){
        var r=deliveryIntake();for(int i=0;i<8;i++)assertThat(deliver(r,Participant.PAYMENT,c->{throw new IllegalStateException("fixture unavailable");}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RETRY_REQUIRED);
        assertThat(deliver(r,Participant.PAYMENT,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.LIMIT_REACHED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(8);
        for(String sql:List.of("DELETE FROM commerce_retention_hold_delivery_attempts","UPDATE commerce_retention_hold_delivery_attempts SET attempt_no=1"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void holdDeliveryReceiptCannotBorrowAnotherSourcePlanOrChangeIdentity()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();var recorded=deliver(r,Participant.PAYMENT,http::deliverHold);
            var inventory=deliver(r,Participant.INVENTORY,c->{throw new IllegalStateException("fixture unavailable");});
            assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_delivery_receipts(request_id,source,source_request_id,source_hold_id,attempt_id) VALUES(?,'INVENTORY',?,?,?)",r.requestId(),r.sourceRequests().get(Participant.PAYMENT),UUID.randomUUID(),inventory.attemptId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("INSERT INTO commerce_retention_hold_delivery_receipts(request_id,source,source_request_id,source_hold_id,attempt_id) VALUES(?,'INVENTORY',?,?,?)",r.requestId(),r.sourceRequests().get(Participant.INVENTORY),UUID.randomUUID(),recorded.attemptId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            for(String sql:List.of("DELETE FROM commerce_retention_hold_delivery_receipts","UPDATE commerce_retention_hold_delivery_receipts SET source_hold_id=gen_random_uuid()"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    @Test void holdDeliveryConcurrentCallsShareOneSourceHoldAndImmutableReceipt()throws Exception{
        try(var http=http();var pool=Executors.newFixedThreadPool(2)){
            var r=deliveryIntake();var arrived=new CountDownLatch(2);var release=new CountDownLatch(1);
            http.afterDispatch=c->{if(c.path().equals(SourceHoldDeliveryProtocol.PATH))holdResponse(arrived,release);};
            var a=pool.submit(()->deliver(r,Participant.PAYMENT,http::deliverHold));var b=pool.submit(()->deliver(r,Participant.PAYMENT,http::deliverHold));
            try{
                assertThat(arrived.await(8,TimeUnit.SECONDS)).isTrue();release.countDown();var first=a.get(8,TimeUnit.SECONDS);var second=b.get(8,TimeUnit.SECONDS);
                assertThat(List.of(first.code(),second.code())).containsExactlyInAnyOrder(CommerceRetentionHoldDelivery.Code.RECORDED,CommerceRetentionHoldDelivery.Code.ALREADY_RECORDED);
                assertThat(first.sourceHoldId()).isEqualTo(second.sourceHoldId());assertThat(db.get(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isEqualTo(1);
            }finally{release.countDown();}
        }
    }
    javax.sql.DataSource deliveryCommitLoss(String pattern,java.util.concurrent.atomic.AtomicBoolean lose){
        return deliveryCommitLoss(ds,pattern,lose);
    }
    javax.sql.DataSource deliveryCommitLoss(javax.sql.DataSource actual,String pattern,java.util.concurrent.atomic.AtomicBoolean lose){
        return new DelegatingDataSource(actual){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                var c=actual.getConnection();boolean[] target={false};return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql && sql.contains(pattern))target[0]=true;
                    Object result;try{result=method.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("commit") && target[0] && lose.compareAndSet(true,false))throw new java.sql.SQLException("fixture acknowledgement lost","08006");return result;
                });
            }
        };
    }
    @Test void holdDeliveryActualHttpTimeoutAfterCommitRemainsPendingUntilExplicitRetry()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            var r=deliveryIntake();var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            http.afterDispatch=c->{if(c.path().equals(SourceHoldDeliveryProtocol.PATH))holdResponse(arrived,release);};
            var pending=pool.submit(()->deliver(r,Participant.PAYMENT,http::deliverHold));
            try{
                assertThat(arrived.await(8,TimeUnit.SECONDS)).isTrue();assertThat(db.get(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
                assertThat(pending.get(8,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RETRY_REQUIRED);assertThat(release.getCount()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();assertThat(holdCalls(http)).isEqualTo(1);
            }finally{http.afterDispatch=c->{};release.countDown();}
            assertThat(deliver(r,Participant.PAYMENT,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RECORDED);
            assertThat(db.get(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);assertThat(holdCalls(http)).isEqualTo(2);
        }
    }
    @Test void holdDeliveryLostAttemptCommitAcknowledgementNeverSendsRpc()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var uncertain=deliveryCommitLoss("INSERT INTO commerce_retention_hold_delivery_attempts",lose);
            var result=new CommerceRetentionHoldDelivery(uncertain,new DataSourceTransactionManager(uncertain)).deliver(10,r.requestId(),r.caseReference(),Participant.PAYMENT,c->{throw new AssertionError("Unknown attempt commit must not send");});
            assertThat(result.code()).isEqualTo(CommerceRetentionHoldDelivery.Code.UNAVAILABLE);assertThat(lose.get()).isFalse();assertThat(holdCalls(http)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_attempts",Long.class)).isEqualTo(1);
            assertThat(deliver(r,Participant.PAYMENT,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RECORDED);assertThat(holdCalls(http)).isEqualTo(1);
        }
    }
    @Test void holdDeliveryLostReceiptCommitAcknowledgementReusesStoredReceiptWithoutRpc()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();var lose=new java.util.concurrent.atomic.AtomicBoolean(true);var uncertain=deliveryCommitLoss("INSERT INTO commerce_retention_hold_delivery_receipts",lose);
            var result=new CommerceRetentionHoldDelivery(uncertain,new DataSourceTransactionManager(uncertain)).deliver(10,r.requestId(),r.caseReference(),Participant.PAYMENT,http::deliverHold);
            assertThat(result.code()).isEqualTo(CommerceRetentionHoldDelivery.Code.UNAVAILABLE);assertThat(lose.get()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isEqualTo(1);
            assertThat(deliver(r,Participant.PAYMENT,c->{throw new AssertionError();}).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.ALREADY_RECORDED);assertThat(holdCalls(http)).isEqualTo(1);
        }
    }
    @Test void holdDeliveryReceiptRollbackDoesNotUndoSourceHoldAndRequiresRetry()throws Exception{
        try(var http=http()){
            var r=deliveryIntake();jdbc.execute("CREATE FUNCTION fixture_hold_receipt_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-only'; END; $$");
            jdbc.execute("CREATE TRIGGER fixture_hold_receipt_failure BEFORE INSERT ON commerce_retention_hold_delivery_receipts FOR EACH ROW EXECUTE FUNCTION fixture_hold_receipt_failure()");
            try{assertThat(deliver(r,Participant.PAYMENT,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.UNAVAILABLE);}
            finally{jdbc.execute("DROP TRIGGER fixture_hold_receipt_failure ON commerce_retention_hold_delivery_receipts");jdbc.execute("DROP FUNCTION fixture_hold_receipt_failure()");}
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_hold_delivery_receipts",Long.class)).isZero();assertThat(db.get(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
            assertThat(deliver(r,Participant.PAYMENT,http::deliverHold).code()).isEqualTo(CommerceRetentionHoldDelivery.Code.RECORDED);assertThat(db.get(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
        }
    }
    @Test void holdDeliverySourceCannotAcknowledgeBeforeAnOuterTransactionCommits(){
        var m=sourceManager(Participant.PAYMENT);var receiver=new SourceHoldDeliveryReceiver(Participant.PAYMENT,m.getDataSource(),m);
        var c=new SourceHoldDeliveryProtocol.Command(Participant.PAYMENT,10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
        new TransactionTemplate(m).executeWithoutResult(tx->assertThatThrownBy(()->receiver.deliver(c)).hasMessage("SOURCE_HOLD_DELIVERY_CALLER_TRANSACTION"));
        assertThat(db.get(Participant.PAYMENT).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
    }
    @Test void localHoldIntakePlansCanBeAppliedExplicitlyWithoutInventingDeliveryAcknowledgements(){
        var store=new CommerceRetentionHoldIntake(ds,manager);var request=UUID.randomUUID();var caseRef=UUID.randomUUID();
        var registered=store.register(10,request,caseRef,Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        outsideSql();assertThat(registered.sourceRequests().keySet()).isEqualTo(CommerceRetentionSourceHolds.SOURCES);
        for(var source:registered.sourceRequests().keySet())assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
        for(var entry:registered.sourceRequests().entrySet()){
            outsideSql();var id=holdRegistry(entry.getKey()).place(10,entry.getValue(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
            assertThat(holdRegistry(entry.getKey()).place(10,entry.getValue(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST)).isEqualTo(id);
            assertThat(sourceSql(entry.getKey()).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
        }
        var again=store.register(10,request,caseRef,Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);
        assertThat(again.sourceRequests()).isEqualTo(registered.sourceRequests());assertThat(again.remoteDeliveryConfirmed()).isFalse();assertThat(again.activationAllowed()).isFalse();
    }
    @Test void sevenSourceGuardReadsAreHistoricalAndNeverConfirmGlobalClearance()throws Exception{
        try(var http=http()){
            for(var source:PeerRetentionGuardProtocol.SOURCES){
                var result=workflow.reviewSourceGuards(subject,Phase.REDACTION,source,c->{outsideSql();return http.guards(c);});
                assertThat(result.code()).as(source.name()).isEqualTo(CommerceRetentionWorkflow.Code.WAITING_DEPENDENCY);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts WHERE generation=?",Long.class,subject.chainGeneration())).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts WHERE case_state<>'UNKNOWN' OR retry_state<>'UNKNOWN'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE state='CONFIRMED'",Long.class)).isZero();
            assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_source_receipts SET retry_state='BLOCKED'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM commerce_retention_source_receipts")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(workflow.reviewSourceGuards(subject,Phase.REDACTION,Participant.INVENTORY,http::guards).code()).isEqualTo(CommerceRetentionWorkflow.Code.WAITING_DEPENDENCY);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts",Long.class)).isEqualTo(7);
        }
    }
    @ParameterizedTest @ValueSource(strings={"RESERVED","CONFIRMED","RELEASED","ABSENT"})
    void inventoryGuardReportsOnlyCanonicalBlockersAndNeverAbsenceClearance(String status)throws Exception{
        if(status.equals("ABSENT"))inventorySql.update("DELETE FROM inventory_reservation_items");
        if(status.equals("ABSENT"))inventorySql.update("DELETE FROM inventory_reservations");
        else inventorySql.update("UPDATE inventory_reservations SET status=?",status);
        var previous=subject;var prepared=workflow.prepare(10,0,previous.chainGeneration(),UUID.randomUUID(),Phase.REDACTION);
        assertThat(prepared.code()).isEqualTo(CommerceRetentionWorkflow.Code.PREPARED);subject=prepared.subject();
        var bound=new CommerceRetentionSourceOutbox(inventoryDs,inventoryManager).bind(subject,Phase.REDACTION,3);
        jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=?,source_revision=?,invalidated=FALSE WHERE generation=? AND source='INVENTORY'",bound.epoch(),bound.revision(),subject.chainGeneration());
        try(var http=http()){
            var command=inventoryGuardCommand();var reply=http.guards(command);
            assertThat(reply.code()).isEqualTo(PeerRetentionGuardProtocol.Code.OBSERVED);
            assertThat(reply.states().get(Guard.NO_ACTIVE_RETRY_OR_DLQ)).isEqualTo(status.equals("RESERVED")?State.BLOCKED:State.UNKNOWN);
            assertThat(reply.states().get(Guard.NO_OPEN_OR_REOPENED_CASE)).isEqualTo(State.UNKNOWN);
            assertThat(reply.states().get(Guard.NO_PRESERVATION_HOLD)).isEqualTo(State.UNKNOWN);
            var result=new CommerceRetentionSourceGuardCollector(ds,manager).collect(subject,Phase.REDACTION,Participant.INVENTORY,c->{outsideSql();return http.guards(c);});
            assertThat(result).isEqualTo(status.equals("RESERVED")?CommerceRetentionSourceGuardCollector.Result.BLOCKED:CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
            assertThat(jdbc.queryForObject("SELECT retry_state FROM commerce_retention_source_receipts WHERE generation=? AND source='INVENTORY'",String.class,subject.chainGeneration())).isEqualTo(status.equals("RESERVED")?"BLOCKED":"UNKNOWN");
        }
    }
    @Test void inventoryGuardHttpBoundsAuthorizesAndNeverCaches()throws Exception{
        try(var http=http()){
            var c=inventoryGuardCommand();byte[] raw=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(c);
            for(String token:List.of("customer","operator","other"))assertThat(http.guardRequest(Participant.INVENTORY,token,"POST",raw).statusCode()).isEqualTo(403);
            assertThat(http.guardRequest(Participant.INVENTORY,null,"POST",raw).statusCode()).isEqualTo(401);
            assertThat(http.guardRequest(Participant.INVENTORY,"order","GET",raw).statusCode()).isEqualTo(403);
            assertThat(http.guardRequest(Participant.INVENTORY,"order","POST",new byte[PeerRetentionGuardProtocol.REQUEST_LIMIT+1]).statusCode()).isEqualTo(413);
            assertThat(http.guardRequest(Participant.INVENTORY,"order","POST","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
            var response=http.guardRequest(Participant.INVENTORY,"order","POST",raw);assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store","private");
            assertThat(PeerRetentionGuardProtocol.parseReply(response.body(),c).states().values()).containsOnly(State.UNKNOWN);
        }
    }
    @ParameterizedTest @ValueSource(strings={"epoch","revision","generation","version","phase","challenge","expired","positive"})
    void inventoryGuardRejectsStaleOrUnscopedRepliesWithoutArchiving(String mismatch)throws Exception{
        try(var http=http()){
            var result=new CommerceRetentionSourceGuardCollector(ds,manager).collect(subject,Phase.REDACTION,Participant.INVENTORY,c->{
                outsideSql();var r=http.guards(c);var s=r.scope();
                var changed=new PeerRetentionProtocol.Scope(s.source(),s.orderId(),mismatch.equals("generation")?UUID.randomUUID():s.generation(),s.orderVersion()+(mismatch.equals("version")?1:0),mismatch.equals("phase")?Phase.MINIMUM_REMOVAL:s.phase(),s.epoch()+(mismatch.equals("epoch")?1:0),s.revision()+(mismatch.equals("revision")?1:0));
                var states=new EnumMap<Guard,State>(Guard.class);states.putAll(r.states());if(mismatch.equals("positive"))states.put(Guard.NO_ACTIVE_RETRY_OR_DLQ,State.CONFIRMED);
                return new PeerRetentionGuardProtocol.Reply(changed,r.policy(),mismatch.equals("challenge")?UUID.randomUUID():r.challenge(),r.code(),mismatch.equals("expired")?Instant.now().minusSeconds(60):r.observedAt(),states);
            });
            assertThat(result).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts",Long.class)).isZero();
        }
    }
    @ParameterizedTest @ValueSource(strings={"revision","quarantine"})
    void inventoryGuardSourceChangeAfterCommandCreationPreventsReceipt(String change)throws Exception{
        try(var http=http()){
            var result=new CommerceRetentionSourceGuardCollector(ds,manager).collect(subject,Phase.REDACTION,Participant.INVENTORY,c->{
                outsideSql();
                if(change.equals("quarantine"))inventorySql.update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE");
                else inventorySql.update("UPDATE inventory_reservation_items SET quantity=3");
                return http.guards(c);
            });
            assertThat(result).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts",Long.class)).isZero();
        }
    }
    @Test void inventoryGuardRootBindingChangeAfterResponsePreventsReceipt()throws Exception{
        try(var http=http()){
            var result=new CommerceRetentionSourceGuardCollector(ds,manager).collect(subject,Phase.REDACTION,Participant.INVENTORY,c->{
                outsideSql();var reply=http.guards(c);
                // A BOUND receipt is immutable. Restore quarantine is a permitted monotonic change.
                jdbc.update("UPDATE commerce_retention_bindings SET restore_blocked=TRUE WHERE generation=? AND source='INVENTORY'",subject.chainGeneration());
                return reply;
            });
            assertThat(result).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts",Long.class)).isZero();
        }
    }
    @Test void completeHttpRoundUsesSevenActualSourcesAndNeverAuthorizesMutation()throws Exception{
        try(var http=http()){
            prepareIntent(http);
            http.afterDispatch=c->{if(c.command()!=null && c.command().action()==Action.VERIFY && c.source()==Participant.NOTIFICATION){
                assertThatThrownBy(()->inventorySql.update("UPDATE inventory_reservation_items SET quantity=3" )).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->db.get(Participant.DELIVERY).update("UPDATE shipments SET sync_state='PENDING'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            }};
            var result=review(http);
            assertThat(result.review().code()).isEqualTo(CommerceRetentionWorkflow.Code.INTENT_FENCED_ASSESSED);
            assertThat(result.review().observations()).hasSize(8);assertThat(result.review().assessment()).isNotNull();
            assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);
            assertThat(http.calls.stream().filter(c->c.command()==null)).hasSize(7);
            for(var action:Action.values())assertThat(calls(http,action)).isEqualTo(7);
            assertAllReleased();assertThat(result.activationAllowed()).isFalse();assertThat(result.executionTerminated()).isFalse();
            assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_intents",String.class)).isEqualTo("WAITING_AUTHORIZATION");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_intent_events",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT privacy_redacted FROM orders",Boolean.class)).isFalse();
            assertThat(inventorySql.update("UPDATE inventory_reservation_items SET quantity=3")).isOne();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void lostPrepareReplyAfterActualCommitStillReleasesThatSource(Participant source)throws Exception{
        try(var http=http()){
            prepareIntent(http);http.losePrepareReply=source;
            var result=review(http);assertThat(result.review().code()).isEqualTo(CommerceRetentionWorkflow.Code.UNAVAILABLE);
            assertThat(result.review().assessment()).isNull();assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);
            assertThat(result.releases()).containsKey(source);assertThat(calls(http,Action.RELEASE)).isEqualTo(calls(http,Action.PREPARE));
            assertThat(calls(http,Action.VERIFY)).isZero();assertAllReleased();
            assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs WHERE current",Integer.class)).isZero();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void lostReleaseReplyIsUnconfirmedEvenThoughSqlTokenIsGone(Participant source)throws Exception{
        try(var http=http()){
            prepareIntent(http);http.loseReleaseReply=source;
            var result=review(http);assertThat(result.review().code()).isEqualTo(CommerceRetentionWorkflow.Code.INTENT_FENCED_ASSESSED);
            assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.UNCONFIRMED);
            assertThat(result.releases()).containsEntry(source,CommerceRetentionWorkflow.ReleaseStatus.UNCONFIRMED);
            assertThat(calls(http,Action.RELEASE)).isEqualTo(7);assertAllReleased();assertThat(result.activationAllowed()).isFalse();
        }
    }
    @ParameterizedTest @EnumSource(value=Action.class,names={"PREPARE","VERIFY"})
    void concurrentStopDuringInventoryHttpDoesNotWaitForRemoteSqlTransaction(Action action)throws Exception{
        try(var http=http();var pool=Executors.newFixedThreadPool(2)){
            prepareIntent(http);var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            http.afterDispatch=c->{if(c.source()==Participant.INVENTORY && c.command()!=null && c.command().action()==action){
                arrived.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture timed out");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }};
            var future=pool.submit(()->review(http));
            try{
                assertThat(arrived.await(10,TimeUnit.SECONDS)).isTrue();
                var stop=pool.submit(()->workflow.requestIntentStop(subject,Phase.REDACTION,intent,0,UUID.randomUUID()));
                assertThat(stop.get(3,TimeUnit.SECONDS).code()).isEqualTo(CommerceRetentionIntents.Code.UPDATED);
                release.countDown();var result=future.get(10,TimeUnit.SECONDS);
                assertThat(result.review().code()).isEqualTo(CommerceRetentionWorkflow.Code.INTENT_STOPPED);
                assertThat(result.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);assertThat(result.executionTerminated()).isFalse();
                assertThat(calls(http,Action.RELEASE)).isEqualTo(calls(http,Action.PREPARE));assertAllReleased();
            }finally{release.countDown();}
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void actualFenceEndpointRejectsImpersonationAndMalformedScope(Participant source)throws Exception{
        try(var http=http()){
            var binding=jdbc.queryForMap("SELECT bound_epoch,source_revision FROM commerce_retention_bindings WHERE generation=? AND source=?",subject.chainGeneration(),source.name());
            var command=new Command(Action.PREPARE,new Scope(source,10,subject.chainGeneration(),0,Phase.REDACTION,
                ((Number)binding.get("bound_epoch")).longValue(),((Number)binding.get("source_revision")).longValue(),UUID.randomUUID()));
            var json=tools.jackson.databind.json.JsonMapper.builder().build();byte[] body=json.writeValueAsBytes(command);
            for(String token:List.of("customer","operator","other"))assertThat(http.request(source,token,"POST",body).statusCode()).isEqualTo(403);
            assertThat(http.request(source,null,"POST",body).statusCode()).isEqualTo(401);
            assertThat(http.request(source,"order","GET",body).statusCode()).isEqualTo(403);
            assertThat(http.request(source,"order","POST","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
            assertThat(http.request(source,"order","POST",new byte[PeerRetentionFenceProtocol.REQUEST_LIMIT+1]).statusCode()).isEqualTo(413);
            var accepted=http.request(source,"order","POST",body);assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(accepted.headers().firstValue("Cache-Control").orElse("")).contains("no-store").contains("private");
            assertThat(PeerRetentionFenceProtocol.parseReply(accepted.body(),command).status()).isEqualTo(Status.FENCED);
            var release=new Command(Action.RELEASE,command.scope());var released=http.request(source,"order","POST",json.writeValueAsBytes(release));
            assertThat(PeerRetentionFenceProtocol.parseReply(released.body(),release).status()).isEqualTo(Status.RELEASED);assertAllReleased();
        }
    }
    Scope sourceScope(Participant source,UUID token){
        var binding=jdbc.queryForMap("SELECT bound_epoch,source_revision FROM commerce_retention_bindings WHERE generation=? AND source=?",subject.chainGeneration(),source.name());
        return new Scope(source,10,subject.chainGeneration(),0,Phase.REDACTION,((Number)binding.get("bound_epoch")).longValue(),((Number)binding.get("source_revision")).longValue(),token);
    }
    JdbcTemplate sourceSql(Participant source){return source==Participant.INVENTORY?inventorySql:db.get(source);}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void releasedTokenCannotBePreparedAgainOrReleaseANewerLease(Participant source)throws Exception{
        try(var http=http()){
            var old=sourceScope(source,UUID.randomUUID());var newer=sourceScope(source,UUID.randomUUID());
            var prepared=http.fence(new Command(Action.PREPARE,old));assertThat(prepared.status()).isEqualTo(Status.FENCED);
            assertThat(http.fence(new Command(Action.PREPARE,old)).expiresAt()).isEqualTo(prepared.expiresAt());
            assertThat(http.fence(new Command(Action.RELEASE,old)).status()).isEqualTo(Status.RELEASED);
            assertThat(http.fence(new Command(Action.PREPARE,old)).status()).isEqualTo(Status.STALE);
            assertThat(http.fence(new Command(Action.VERIFY,old)).status()).isEqualTo(Status.STALE);
            assertThat(http.fence(new Command(Action.PREPARE,newer)).status()).isEqualTo(Status.FENCED);
            assertThat(http.fence(new Command(Action.RELEASE,old)).status()).isEqualTo(Status.RELEASED);
            assertThat(http.fence(new Command(Action.VERIFY,newer)).status()).isEqualTo(Status.FENCED);
            assertThat(http.fence(new Command(Action.RELEASE,newer)).status()).isEqualTo(Status.RELEASED);
            assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_fence_tokens WHERE released",Long.class)).isEqualTo(2);
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void releaseBeforePrepareBurnsTokenEvenAfterOtherRoundHasFinished(Participant source)throws Exception{
        try(var http=http()){
            var late=sourceScope(source,UUID.randomUUID());var newer=sourceScope(source,UUID.randomUUID());
            assertThat(http.fence(new Command(Action.RELEASE,late)).status()).isEqualTo(Status.RELEASED);
            assertThat(http.fence(new Command(Action.PREPARE,late)).status()).isEqualTo(Status.STALE);
            assertThat(http.fence(new Command(Action.PREPARE,newer)).status()).isEqualTo(Status.FENCED);
            http.fence(new Command(Action.RELEASE,newer));
            assertThat(http.fence(new Command(Action.PREPARE,late)).status()).isEqualTo(Status.STALE);
            assertAllReleased();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void expiredIssuedTokenCannotAcquireAgainAfterItsReplacementExpires(Participant source)throws Exception{
        try(var http=http()){
            var old=sourceScope(source,UUID.randomUUID());var newer=sourceScope(source,UUID.randomUUID());
            assertThat(http.fence(new Command(Action.PREPARE,old)).status()).isEqualTo(Status.FENCED);
            sourceSql(source).update("UPDATE commerce_retention_source_state SET retention_fence_until=clock_timestamp()-interval '1 second'");
            assertThat(http.fence(new Command(Action.PREPARE,newer)).status()).isEqualTo(Status.FENCED);
            sourceSql(source).update("UPDATE commerce_retention_source_state SET retention_fence_until=clock_timestamp()-interval '1 second'");
            assertThat(http.fence(new Command(Action.PREPARE,old)).status()).isEqualTo(Status.STALE);
            http.fence(new Command(Action.RELEASE,old));http.fence(new Command(Action.RELEASE,newer));assertAllReleased();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void concurrentPrepareAndReleaseLeaveTokenPermanentlyClosedAcrossReplicas(Participant source)throws Exception{
        try(var http=http();var pool=Executors.newFixedThreadPool(2)){
            var scope=sourceScope(source,UUID.randomUUID());var start=new CountDownLatch(1);
            var prepare=pool.submit(()->{start.await();return http.fence(new Command(Action.PREPARE,scope));});
            var release=pool.submit(()->{start.await();return http.fence(new Command(Action.RELEASE,scope));});start.countDown();
            assertThat(prepare.get(10,TimeUnit.SECONDS).status()).isIn(Status.FENCED,Status.STALE);
            assertThat(release.get(10,TimeUnit.SECONDS).status()).isEqualTo(Status.RELEASED);
            var replica=new CommerceRetentionWriteFence(source,sourceSql(source).getDataSource(),source==Participant.INVENTORY?inventoryManager:managers.get(source));
            assertThat(replica.apply(new Command(Action.PREPARE,scope)).status()).isEqualTo(Status.STALE);assertAllReleased();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void replayMarkersAreBoundedImmutableAndNeverExpireByAge(Participant source)throws Exception{
        try(var http=http()){
            Scope first=null;
            for(int i=0;i<8;i++){
                var scope=sourceScope(source,UUID.randomUUID());if(first==null)first=scope;
                assertThat(http.fence(new Command(Action.RELEASE,scope)).status()).isEqualTo(Status.RELEASED);
            }
            var ninth=sourceScope(source,UUID.randomUUID());
            assertThat(http.fence(new Command(Action.PREPARE,ninth)).status()).isEqualTo(Status.BLOCKED);
            assertThat(http.fence(new Command(Action.RELEASE,ninth)).status()).isEqualTo(Status.BLOCKED);
            assertThat(http.fence(new Command(Action.RELEASE,first)).status()).isEqualTo(Status.RELEASED);
            assertThat(sourceSql(source).queryForObject("SELECT count(*) FROM commerce_retention_fence_tokens",Long.class)).isEqualTo(8);
            assertThatThrownBy(()->sourceSql(source).update("DELETE FROM commerce_retention_fence_tokens")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->sourceSql(source).update("UPDATE commerce_retention_fence_tokens SET released=FALSE")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertAllReleased();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void releaseOnlyRecoveryAcrossRealHttpKeepsUnknownAckUntilExplicitRetry(Participant source)throws Exception{
        try(var http=http()){
            prepareIntent(http);var rounds=new CommerceRetentionObservationRounds(ds,manager);var recovery=new CommerceRetentionRoundRecovery(ds,manager);
            var round=UUID.randomUUID();assertThat(rounds.begin(subject,Phase.REDACTION,intent,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);
            var scope=sourceScope(source,round);assertThat(rounds.recordAttempt(subject,Phase.REDACTION,intent,0,scope)).isTrue();
            assertThat(http.fence(new Command(Action.PREPARE,scope)).status()).isEqualTo(Status.FENCED);
            http.calls.clear();http.loseReleaseReply=source;
            var first=recovery.recover(subject,Phase.REDACTION,intent,round,http::fence);
            assertThat(first.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.RETRY_REQUIRED);
            assertThat(http.calls.stream().filter(c->c.command()!=null)).allMatch(c->c.command().action()==Action.RELEASE);
            assertThat(rounds.inspect(subject,Phase.REDACTION,intent,round).orElseThrow().leases().get(source).releaseAcknowledged()).isFalse();
            assertAllReleased();assertThat(http.fence(new Command(Action.PREPARE,scope)).status()).isEqualTo(Status.STALE);
            http.calls.clear();http.loseReleaseReply=null;
            var second=recovery.recover(subject,Phase.REDACTION,intent,round,http::fence);
            assertThat(second.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);assertThat(calls(http,Action.RELEASE)).isEqualTo(1);
            assertThat(rounds.inspect(subject,Phase.REDACTION,intent,round).orElseThrow().finished()).isTrue();
            assertThat(second.activationAllowed()).isFalse();assertThat(second.executionTerminated()).isFalse();
            assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs WHERE current",Integer.class)).isZero();
        }
    }
    static java.util.stream.Stream<Arguments> recoveryTimeoutCases(){
        return java.util.stream.Stream.concat(Arrays.stream(Participant.values()).filter(p->p!=Participant.ORDER)
            .map(p->Arguments.of(p,false)),java.util.stream.Stream.of(Arguments.of(Participant.INVENTORY,true)));
    }
    static void holdResponse(CountDownLatch arrived,CountDownLatch release){
        arrived.countDown();
        try{if(!release.await(12,TimeUnit.SECONDS))throw new AssertionError("Fixture response was not released");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    @ParameterizedTest @MethodSource("recoveryTimeoutCases")
    void recoveryHttpTimeoutAfterSourceCommitRetainsPendingUntilExplicitRetry(Participant source,boolean bodyTimeout)throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            prepareIntent(http);var rounds=new CommerceRetentionObservationRounds(ds,manager);var recovery=new CommerceRetentionRoundRecovery(ds,manager);
            var round=UUID.randomUUID();assertThat(rounds.begin(subject,Phase.REDACTION,intent,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);
            var scope=sourceScope(source,round);assertThat(rounds.recordAttempt(subject,Phase.REDACTION,intent,0,scope)).isTrue();
            assertThat(http.fence(new Command(Action.PREPARE,scope)).status()).isEqualTo(Status.FENCED);
            var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            java.util.function.Consumer<RetentionIntentHttpFixture.Call> pause=c->{if(c.source()==source && c.command()!=null && c.command().action()==Action.RELEASE)holdResponse(arrived,release);};
            if(bodyTimeout)http.beforeResponseBody=pause;else http.afterDispatch=pause;
            http.calls.clear();var pending=pool.submit(()->recovery.recover(subject,Phase.REDACTION,intent,round,http::fence));
            try{
                assertThat(arrived.await(8,TimeUnit.SECONDS)).isTrue();assertAllReleased();
                // The real production client's deadline must end the call while the response remains withheld.
                var result=pending.get(8,TimeUnit.SECONDS);assertThat(release.getCount()).isEqualTo(1);
                assertThat(result.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.RETRY_REQUIRED);
                assertThat(result.acknowledgements()).containsEntry(source,false);assertThat(calls(http,Action.RELEASE)).isEqualTo(1);
                var snapshot=rounds.inspect(subject,Phase.REDACTION,intent,round).orElseThrow();
                assertThat(snapshot.finished()).isFalse();assertThat(snapshot.leases().get(source).releaseAcknowledged()).isFalse();
            }finally{http.afterDispatch=c->{};http.beforeResponseBody=c->{};release.countDown();}
            http.calls.clear();var retried=recovery.recover(subject,Phase.REDACTION,intent,round,http::fence);
            assertThat(retried.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);assertThat(calls(http,Action.RELEASE)).isEqualTo(1);
            assertThat(http.fence(new Command(Action.PREPARE,scope)).status()).isEqualTo(Status.STALE);
            assertThat(retried.activationAllowed()).isFalse();assertThat(retried.executionTerminated()).isFalse();
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void recoveryLosesOwnershipWhileActualReleaseResponseIsInFlight(Participant source)throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            prepareIntent(http);var rounds=new CommerceRetentionObservationRounds(ds,manager);var recovery=new CommerceRetentionRoundRecovery(ds,manager);
            var round=UUID.randomUUID();assertThat(rounds.begin(subject,Phase.REDACTION,intent,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);
            var scope=sourceScope(source,round);assertThat(rounds.recordAttempt(subject,Phase.REDACTION,intent,0,scope)).isTrue();
            assertThat(http.fence(new Command(Action.PREPARE,scope)).status()).isEqualTo(Status.FENCED);
            var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            http.afterDispatch=c->{if(c.source()==source && c.command()!=null && c.command().action()==Action.RELEASE)holdResponse(arrived,release);};
            http.calls.clear();var pending=pool.submit(()->recovery.recover(subject,Phase.REDACTION,intent,round,http::fence));
            try{
                assertThat(arrived.await(8,TimeUnit.SECONDS)).isTrue();assertAllReleased();
                assertThat(jdbc.update("UPDATE commerce_retention_observation_rounds SET restore_blocked=TRUE WHERE round_id=?",round)).isEqualTo(1);
                release.countDown();var result=pending.get(8,TimeUnit.SECONDS);
                assertThat(result.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.OWNERSHIP_LOST);assertThat(result.acknowledgements()).isEmpty();
                assertThat(jdbc.queryForObject("SELECT recovery_token IS NULL AND recovery_until IS NULL FROM commerce_retention_observation_rounds WHERE round_id=?",Boolean.class,round)).isTrue();
                var snapshot=rounds.inspect(subject,Phase.REDACTION,intent,round).orElseThrow();
                assertThat(snapshot.quarantined()).isTrue();assertThat(snapshot.finished()).isFalse();assertThat(snapshot.leases().get(source).releaseAcknowledged()).isFalse();
                assertThat(calls(http,Action.RELEASE)).isEqualTo(1);
                assertThat(recovery.recover(subject,Phase.REDACTION,intent,round,c->{throw new AssertionError("Quarantine must not send HTTP");}).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.QUARANTINED);
                assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs WHERE current",Integer.class)).isZero();
            }finally{release.countDown();}
        }
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void recoveryWithChangedSourceBindingKeepsOldAttemptPendingAndNewLeaseUntouched(Participant source)throws Exception{
        try(var http=http()){
            prepareIntent(http);var rounds=new CommerceRetentionObservationRounds(ds,manager);var recovery=new CommerceRetentionRoundRecovery(ds,manager);
            var round=UUID.randomUUID();assertThat(rounds.begin(subject,Phase.REDACTION,intent,0,round)).isEqualTo(CommerceRetentionObservationRounds.Code.CREATED);
            var old=sourceScope(source,round);assertThat(rounds.recordAttempt(subject,Phase.REDACTION,intent,0,old)).isTrue();
            assertThat(http.fence(new Command(Action.PREPARE,old)).status()).isEqualTo(Status.FENCED);
            http.loseReleaseReply=source;assertThat(recovery.recover(subject,Phase.REDACTION,intent,round,http::fence).code()).isEqualTo(CommerceRetentionRoundRecovery.Code.RETRY_REQUIRED);
            http.loseReleaseReply=null;
            var newSubject=new Subject(subject.orderId(),subject.customerId(),subject.orderVersion(),UUID.randomUUID());
            var bound=(source==Participant.INVENTORY?new CommerceRetentionSourceOutbox(inventoryDs,inventoryManager):outboxes.get(source)).bind(newSubject,Phase.REDACTION,old.epoch());
            var next=new Scope(source,subject.orderId(),newSubject.chainGeneration(),subject.orderVersion(),Phase.REDACTION,bound.epoch(),bound.revision(),UUID.randomUUID());
            assertThat(http.fence(new Command(Action.PREPARE,next)).status()).isEqualTo(Status.FENCED);
            http.calls.clear();var result=recovery.recover(subject,Phase.REDACTION,intent,round,http::fence);
            assertThat(result.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.RETRY_REQUIRED);assertThat(result.acknowledgements()).containsEntry(source,false);
            assertThat(calls(http,Action.RELEASE)).isEqualTo(1);assertThat(http.calls.getFirst().command().scope()).isEqualTo(old);
            var snapshot=rounds.inspect(subject,Phase.REDACTION,intent,round).orElseThrow();assertThat(snapshot.finished()).isFalse();
            assertThat(snapshot.leases().get(source).releaseAcknowledged()).isFalse();assertThat(snapshot.leases().get(source).scope()).isEqualTo(old);
            assertThat(sourceSql(source).queryForObject("SELECT retention_fence_token FROM commerce_retention_source_state WHERE order_id=?",UUID.class,subject.orderId())).isEqualTo(next.fenceToken());
            assertThat(http.fence(new Command(Action.VERIFY,next)).status()).isEqualTo(Status.FENCED);
            assertThat(http.fence(new Command(Action.RELEASE,next)).status()).isEqualTo(Status.RELEASED);assertAllReleased();
        }
    }
    @Test void recoveryDuringActualInventoryPrepareStopsOldCoordinatorBeforeAssessment()throws Exception{
        try(var http=http();var pool=Executors.newSingleThreadExecutor()){
            prepareIntent(http);var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            var scope=new java.util.concurrent.atomic.AtomicReference<Scope>();
            http.afterDispatch=c->{if(c.source()==Participant.INVENTORY && c.command()!=null && c.command().action()==Action.PREPARE){
                scope.set(c.command().scope());arrived.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }};
            var old=pool.submit(()->review(http));
            try{
                assertThat(arrived.await(10,TimeUnit.SECONDS)).isTrue();
                var recovered=new CommerceRetentionRoundRecovery(ds,manager).recover(subject,Phase.REDACTION,intent,scope.get().fenceToken(),http::fence);
                assertThat(recovered.code()).isEqualTo(CommerceRetentionRoundRecovery.Code.SETTLED);
                release.countDown();var result=old.get(10,TimeUnit.SECONDS);
                assertThat(result.review().code()).isEqualTo(CommerceRetentionWorkflow.Code.INTENT_ROUND_TAKEN_OVER);assertThat(result.review().assessment()).isNull();
                assertThat(http.fence(new Command(Action.PREPARE,scope.get())).status()).isEqualTo(Status.STALE);assertAllReleased();
                assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs WHERE current",Integer.class)).isZero();
            }finally{release.countDown();}
        }
    }
}
