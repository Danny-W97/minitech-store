package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.demo.platformevents.EventIdentity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionProtocol.*;
import org.demo.platformsecurity.privacy.PeerRetentionProtocol.Code;

@Testcontainers
class CommerceRetentionPeersPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("peer_root").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    static final Map<Participant,JdbcTemplate> db=new EnumMap<>(Participant.class);
    static final Map<Participant,DataSourceTransactionManager> managers=new EnumMap<>(Participant.class);
    static final Map<Participant,CommerceRetentionSourceOutbox> outboxes=new EnumMap<>(Participant.class);
    static final Map<Participant,PeerRetentionEvidence> peers=new EnumMap<>(Participant.class);
    static final String EVENT="payment-"+EventIdentity.hash("PAYMENT_FAILED:10:fixture@example.test");
    CommerceRetentionJobs jobs;CommerceRetentionBindings bindings;CommerceRetentionPeerCollector collector;Subject scope;
    @BeforeAll static void setup(){
        initialize(DB);
    }
    static void initialize(PostgreSQLContainer<?> database){
        ds=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        Path root=Path.of("").toAbsolutePath();if(!Files.isDirectory(root.resolve("returns-service")))root=root.getParent();
        for(var p:PEERS){String name=p.name().toLowerCase(Locale.ROOT);jdbc.execute("CREATE DATABASE peer_"+name);var local=new DriverManagerDataSource(database.getJdbcUrl().replace("/"+database.getDatabaseName(),"/peer_"+name),database.getUsername(),database.getPassword());Flyway.configure().dataSource(local).locations("filesystem:"+root.resolve(name+"-service/src/main/resources/db/migration")).load().migrate();var m=new DataSourceTransactionManager(local);db.put(p,new JdbcTemplate(local));managers.put(p,m);outboxes.put(p,new CommerceRetentionSourceOutbox(local,m));peers.put(p,new PeerRetentionEvidence(p,local,m));}
    }
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE orders,commerce_retention_jobs,commerce_retention_bindings,commerce_retention_evidence,commerce_retention_payment_evidence,commerce_retention_order_state,commerce_retention_holds,commerce_retention_hold_scopes,account_privacy_fences,commerce_privacy_locks RESTART IDENTITY CASCADE");
        Map<Participant,String> tables=Map.of(Participant.RETURNS,"return_requests,return_lines,return_events",Participant.PROMOTION,"coupon_reservations,promotion_redemptions",Participant.DELIVERY,"shipments",Participant.PAYMENT,"payments,payment_outbox_events,payment_follow_ups,payment_refunds,stripe_webhook_receipts,payment_order_gates",Participant.BILLING,"invoices,invoice_items,invoice_corrections,invoice_outbox_events,event_inbox,event_identity_aliases",Participant.NOTIFICATION,"notifications,notification_dead_letters,event_inbox,event_identity_aliases");
        for(var p:PEERS)db.get(p).execute("TRUNCATE "+tables.get(p)+",commerce_retention_source_state,commerce_retention_change_outbox,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");
        jobs=new CommerceRetentionJobs(ds,manager);bindings=new CommerceRetentionBindings(ds,manager);collector=new CommerceRetentionPeerCollector(ds,manager);
        jdbc.update("INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,delivery_tracking_number,delivery_status,delivery_revision,promotion_code,coupon_reservation_verified,created_at,updated_at) VALUES(10,17,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','123','COURIER',0,0,'HOME_DELIVERY','fixture',?,'customer:17','TRACK','CANCELLED',3,'FIXTURE',TRUE,now(),now())","a".repeat(64));
        jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,sku,quantity,unit_price,total_price) VALUES(10,1,'Fixture','FIX',2,5,10)");jdbc.update("INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES(10,'FAILED',now(),now())");
        jdbc.update("UPDATE orders SET inventory_reservation_id=1 WHERE id=10");
        scope=jobs.prepare(10,Phase.REDACTION);bind(scope,0);for(var p:PEERS)seed(p);jobs.invalidate(10,scope.chainGeneration());scope=jobs.prepare(10,Phase.REDACTION);bind(scope,1);for(var peer:PEERS)awaitSourceClock(peer);
    }
    void awaitSourceClock(Participant p){
        var time=db.get(p).queryForObject("SELECT local_reconciled_at FROM commerce_retention_source_state WHERE order_id=10",java.sql.Timestamp.class);
        if(time==null)return;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(time.toInstant().isAfter(java.time.Instant.now()) && System.nanoTime()<deadline){try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
        assertThat(time.toInstant().isAfter(java.time.Instant.now())).withFailMessage("Fixture DB/JVM clock skew did not settle: %s",time).isFalse();
    }
    void bind(Subject s,long epoch){assertThat(bindings.prepare(s,Phase.REDACTION)).isEqualTo(CommerceRetentionBindings.Preparation.PREPARED);for(var p:PEERS){var b=outboxes.get(p).bind(s,Phase.REDACTION,epoch);jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=?,source_revision=?,invalidated=FALSE WHERE generation=? AND source=?",b.epoch(),b.revision(),s.chainGeneration(),p.name());}jdbc.update("UPDATE commerce_retention_bindings SET state='BOUND',bound_epoch=?,source_revision=0,invalidated=FALSE WHERE generation=? AND source='INVENTORY'",epoch+1,s.chainGeneration());}
    void fresh(){jobs.invalidate(10,scope.chainGeneration());scope=jobs.prepare(10,Phase.REDACTION);long epoch=db.get(Participant.PAYMENT).queryForObject("SELECT binding_epoch FROM commerce_retention_source_state",Long.class);bind(scope,epoch);}
    CommerceRetentionSourceHolds sourceHolds(){return new CommerceRetentionSourceHolds(Participant.RETURNS,managers.get(Participant.RETURNS).getDataSource(),managers.get(Participant.RETURNS));}
    PeerRetentionGuardProtocol.Command returnsGuardCommand(){
        var b=jdbc.queryForMap("SELECT bound_epoch,source_revision FROM commerce_retention_bindings WHERE generation=? AND source='RETURNS'",scope.chainGeneration());
        return new PeerRetentionGuardProtocol.Command(new Scope(Participant.RETURNS,10,scope.chainGeneration(),0,Phase.REDACTION,((Number)b.get("bound_epoch")).longValue(),((Number)b.get("source_revision")).longValue()),POLICY,UUID.randomUUID());
    }
    @Test void sourceHoldIdempotencyAndConflictingReplayAreAtomic(){
        var q=db.get(Participant.RETURNS);var request=UUID.randomUUID();long before=q.queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class);
        var id=sourceHolds().place(10,request,Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
        assertThat(sourceHolds().place(10,request,Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST)).isEqualTo(id);
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class)).isEqualTo(before+1);
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_holds",Long.class)).isEqualTo(before+1);
        assertThat(q.queryForObject("SELECT invalidated FROM commerce_retention_source_state",Boolean.class)).isTrue();
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_change_outbox WHERE binding_epoch=2",Long.class)).isEqualTo(before+1);
        assertThatThrownBy(()->sourceHolds().place(10,request,Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionSourceHolds.Reason.OPEN_CASE)).hasMessage("RETENTION_SOURCE_HOLD_REQUEST_CONFLICT");
        assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);
        assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class)).isEqualTo(before+1);
    }
    @Test void sourceHoldHistoryAndDatabaseRevisionCannotBeForged(){
        guardCollector().collect(scope,Phase.REDACTION,Participant.RETURNS,peers.get(Participant.RETURNS)::guards);
        var q=db.get(Participant.RETURNS);sourceHolds().place(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
        for(String sql:List.of("DELETE FROM commerce_retention_source_holds","UPDATE commerce_retention_source_holds SET guard='NO_ACTIVE_RETRY_OR_DLQ'","UPDATE commerce_retention_source_holds SET source_revision=99"))
            assertThatThrownBy(()->q.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->q.update("INSERT INTO commerce_retention_source_holds(hold_id,order_id,guard,reason_code,request_id,source_revision) VALUES(?,10,'NO_PRESERVATION_HOLD','PRESERVATION_REQUEST',?,99)",UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_source_receipts SET hold_state='CONFIRMED'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void sourceHoldUnknownTargetsAndUnsupportedGuardsCannotCreateRows(){
        for(Runnable operation:List.<Runnable>of(
            ()->sourceHolds().place(999,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST),
            ()->sourceHolds().place(10,UUID.randomUUID(),Guard.INDEPENDENT_JOURNAL_CURRENT,CommerceRetentionSourceHolds.Reason.DEPENDENCY_REVIEW),
            ()->sourceHolds().place(0,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST),
            ()->new CommerceRetentionSourceHolds(Participant.ORDER,ds,manager)))assertThatThrownBy(operation::run).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.get(Participant.RETURNS).queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
    }
    @Test void sourceHoldRollbackRestoresRevisionAndOutbox(){
        var q=db.get(Participant.RETURNS);var state=q.queryForMap("SELECT * FROM commerce_retention_source_state");var outbox=q.queryForList("SELECT * FROM commerce_retention_change_outbox ORDER BY binding_epoch");
        new TransactionTemplate(managers.get(Participant.RETURNS)).executeWithoutResult(tx->{sourceHolds().place(10,UUID.randomUUID(),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionSourceHolds.Reason.OPEN_CASE);tx.setRollbackOnly();});
        assertThat(q.queryForMap("SELECT * FROM commerce_retention_source_state")).isEqualTo(state);assertThat(q.queryForList("SELECT * FROM commerce_retention_change_outbox ORDER BY binding_epoch")).isEqualTo(outbox);
        assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isZero();
    }
    @ParameterizedTest @EnumSource(value=Guard.class,names={"NO_PRESERVATION_HOLD","NO_OPEN_OR_REOPENED_CASE","NO_ACTIVE_RETRY_OR_DLQ"})
    void sourceHoldBlocksAfterRebindingAndIsArchivedAcrossActualHttp(Guard guard)throws Exception{
        var old=returnsGuardCommand();sourceHolds().place(10,UUID.randomUUID(),guard,CommerceRetentionSourceHolds.Reason.DEPENDENCY_REVIEW);
        assertThat(peers.get(Participant.RETURNS).guards(old).code()).isEqualTo(PeerRetentionGuardProtocol.Code.STALE);fresh();
        try(var http=new RetentionGuardHttpFixture(peers)){
            assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.RETURNS,c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return http.client(Participant.RETURNS).guards(c);})).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
            assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE kind='GUARD' AND source=?",String.class,guard.name())).isEqualTo("BLOCKED");
            assertThat(jdbc.queryForObject("SELECT hold_state FROM commerce_retention_source_receipts",String.class)).isEqualTo(guard==Guard.NO_PRESERVATION_HOLD?"BLOCKED":"UNKNOWN");
            assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.RETURNS,http.client(Participant.RETURNS)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts",Long.class)).isEqualTo(1);
        }
    }
    @Test void sourceHoldInvalidatesAnActiveObservationWithoutWeakeningTheBusinessFence(){
        var p=Participant.RETURNS;var token=UUID.randomUUID();var prepared=fenceCommand(p,token,PeerRetentionFenceProtocol.Action.PREPARE);
        assertThat(peers.get(p).fence(prepared).status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        sourceHolds().place(10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);
        assertThat(peers.get(p).fence(new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.VERIFY,prepared.scope())).status()).isEqualTo(PeerRetentionFenceProtocol.Status.STALE);
        assertThat(db.get(p).queryForObject("SELECT retention_fence_token FROM commerce_retention_source_state",UUID.class)).isEqualTo(token);
        assertThatThrownBy(()->db.get(p).update("UPDATE return_requests SET description='must remain fenced'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void sourceHoldConcurrentDuplicateRequestsAdvanceOnlyOneRevision()throws Exception{
        var q=db.get(Participant.RETURNS);long before=q.queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class);var request=UUID.randomUUID();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<UUID> task=()->{assertThat(start.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return sourceHolds().place(10,request,Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);};
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(q.queryForObject("SELECT count(*) FROM commerce_retention_source_holds",Long.class)).isEqualTo(1);assertThat(q.queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class)).isEqualTo(before+1);
    }
    @Test void sourceHoldUnknownStateKeepsLegacyV1ReceiptIdempotent(){
        var c=returnsGuardCommand();var s=c.scope();String oldHash=org.demo.platformsecurity.Access.hash("A43/source-guard-observation/1\nRETURNS\n10\n"+s.generation()+"\n0\nREDACTION\n"+s.epoch()+"\n"+s.revision()+"\n"+POLICY+"\nOBSERVED\nUNKNOWN\nUNKNOWN\n");
        jdbc.update("INSERT INTO commerce_retention_source_receipts(order_id,generation,phase,source,order_version,policy,binding_epoch,source_revision,receipt_id,observation_hash,case_state,retry_state,observed_at) VALUES(10,?,'REDACTION','RETURNS',0,?,?,?,?,?,'UNKNOWN','UNKNOWN',clock_timestamp())",s.generation(),POLICY,s.epoch(),s.revision(),UUID.randomUUID(),oldHash);
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.RETURNS,peers.get(Participant.RETURNS)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
        assertThat(jdbc.queryForObject("SELECT observation_hash FROM commerce_retention_source_receipts",String.class)).isEqualTo(oldHash);assertThat(jdbc.queryForObject("SELECT hold_state FROM commerce_retention_source_receipts",String.class)).isEqualTo("UNKNOWN");
    }
    @Test void sourceHoldChangedAtTheSameRevisionCannotReuseAnUnknownReceipt(){
        guardCollector().collect(scope,Phase.REDACTION,Participant.RETURNS,peers.get(Participant.RETURNS)::guards);
        var original=jdbc.queryForMap("SELECT * FROM commerce_retention_source_receipts");
        var result=guardCollector().collect(scope,Phase.REDACTION,Participant.RETURNS,c->{var r=peers.get(Participant.RETURNS).guards(c);var states=new EnumMap<Guard,State>(Guard.class);states.putAll(r.states());states.put(Guard.NO_PRESERVATION_HOLD,State.BLOCKED);return PeerRetentionGuardProtocol.reply(c,r.code(),r.observedAt(),states);});
        assertThat(result).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);assertThat(jdbc.queryForMap("SELECT * FROM commerce_retention_source_receipts")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isFalse();
    }
    void seed(Participant p){var q=db.get(p);new TransactionTemplate(managers.get(p)).executeWithoutResult(tx->{switch(p){
        case DELIVERY->q.update("INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,sync_state,progress_revision,created_at,updated_at) VALUES(10,'TRACK','Fixture','COURIER','CANCELLED','SYNCED',3,now(),now())");
        case PROMOTION->q.update("INSERT INTO coupon_reservations(order_id,code,subtotal,discount_amount,state,created_at,updated_at) VALUES(10,'FIXTURE',10,0,'RELEASED',now(),now())");
        case RETURNS->{q.update("INSERT INTO return_requests(id,order_id,customer_id,customer_email,request_type,reason,description,status,version,request_hash,order_verified_at,created_at,updated_at) VALUES(1,10,17,'fixture@example.test','RETURN','Fixture','Fixture','REJECTED',1,?,now(),now(),now())","b".repeat(64));q.update("INSERT INTO return_lines(return_id,order_item_id,product_id,product_name,ordered_quantity,quantity) VALUES(1,1,1,'Fixture',2,1)");q.update("INSERT INTO return_events(return_id,revision,operation_id,action,to_status,actor_ref,created_at) VALUES(1,1,?,'REJECT','REJECTED','fixture',now())",UUID.randomUUID());}
        case PAYMENT->{q.update("INSERT INTO payments(id,order_id,amount,currency,payment_method,status,idempotency_key,provider_declined,request_fingerprint,result_recorded_at,created_at,updated_at) VALUES(1,10,10,'PLN','CARD','FAILED','fixture-payment-key',TRUE,?,now(),now(),now())","c".repeat(64));q.update("INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,attempts,next_attempt_at,created_at,updated_at,published_at) VALUES('PAYMENT_FAILED:10:fixture@example.test','PAYMENT_FAILED',10,10,'fixture@example.test','PUBLISHED',1,now(),now(),now(),now())");}
        case BILLING->{q.update("INSERT INTO invoices(id,order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,currency,status,created_at) VALUES(1,10,17,'fixture@example.test','FIXTURE-1','PERSON','Fixture','Street','00-001','City','PL',10,0,10,'PLN','CANCELLED',now())");q.update("INSERT INTO invoice_items(invoice_id,position_no,product_id,product_name,quantity,unit_price,total_price) VALUES(1,1,1,'Fixture',2,5,10)");q.update("INSERT INTO event_inbox(event_id,payload_hash,status,related_id,order_id,received_at) VALUES(?,?,'ACCEPTED',10,10,now())",EVENT,"d".repeat(64));}
        case NOTIFICATION->{q.update("INSERT INTO notifications(id,event_type,source_id,recipient,channel,subject,message,status,sent_at,created_at,updated_at) VALUES(1,'PAYMENT_FAILED',10,'fixture@example.test','EMAIL','Fixture','Fixture','SENT',now(),now(),now())");q.update("INSERT INTO event_inbox(event_id,payload_hash,status,related_id,order_id,received_at) VALUES(?,?,'ACCEPTED',1,10,now())",EVENT,"e".repeat(64));}
        default->throw new AssertionError();}});}
    CommerceRetentionSourceGuardCollector guardCollector(){return new CommerceRetentionSourceGuardCollector(ds,manager);}
    @Test void sourceObservationsAreBoundedImmutableAndDoNotAdvanceAssessmentProofs(){
        for(var source:PEERS){
            for(int retry=0;retry<3;retry++)assertThat(guardCollector().collect(scope,Phase.REDACTION,source,peers.get(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts WHERE generation=?",Long.class,scope.chainGeneration())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts WHERE case_state='UNKNOWN' AND retry_state='UNKNOWN'",Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,scope.chainGeneration())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_source_receipts SET observation_hash=repeat('a',64)")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM commerce_retention_source_receipts")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void contradictoryObservationAtTheSameSourceRevisionRetiresGenerationAndKeepsHistory(){
        var source=Participant.DELIVERY;
        guardCollector().collect(scope,Phase.REDACTION,source,peers.get(source)::guards);
        var original=jdbc.queryForMap("SELECT * FROM commerce_retention_source_receipts WHERE generation=?",scope.chainGeneration());
        var result=guardCollector().collect(scope,Phase.REDACTION,source,c->{
            var r=peers.get(source).guards(c);var states=new EnumMap<Guard,State>(Guard.class);states.putAll(r.states());states.put(Guard.NO_ACTIVE_RETRY_OR_DLQ,State.BLOCKED);
            return PeerRetentionGuardProtocol.reply(c,r.code(),r.observedAt(),states);
        });
        assertThat(result).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);
        assertThat(jdbc.queryForMap("SELECT * FROM commerce_retention_source_receipts WHERE generation=?",scope.chainGeneration())).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }
    @Test void receiptHistoryCannotBorrowAnotherOrderVersionOrBeWrittenOutsideATransaction(){
        var source=Participant.DELIVERY;
        guardCollector().collect(scope,Phase.REDACTION,source,peers.get(source)::guards);
        assertThatThrownBy(()->jdbc.update("""
                INSERT INTO commerce_retention_source_receipts SELECT order_id,generation,phase,'BILLING',order_version+1,
                policy,binding_epoch,source_revision,?,observation_hash,case_state,retry_state,observed_at,recorded_at,hold_state
                FROM commerce_retention_source_receipts WHERE generation=? AND source='DELIVERY'
                """,UUID.randomUUID(),scope.chainGeneration())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->new CommerceRetentionSourceReceipts(ds).record(null,null)).hasMessage("RETENTION_RECEIPT_TRANSACTION_REQUIRED");
    }
    @Test void oldReceiptDoesNotReplaceAFreshSourceReadAfterInvalidation(){
        var source=Participant.DELIVERY;guardCollector().collect(scope,Phase.REDACTION,source,peers.get(source)::guards);
        db.get(source).update("UPDATE shipments SET sync_state='PENDING' WHERE order_id=10");
        assertThat(guardCollector().collect(scope,Phase.REDACTION,source,peers.get(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts WHERE generation=?",Long.class,scope.chainGeneration())).isEqualTo(1);
        fresh();
        assertThat(guardCollector().collect(scope,Phase.REDACTION,source,peers.get(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts WHERE order_id=10",Long.class)).isEqualTo(2);
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"RETURNS","PAYMENT","PROMOTION","DELIVERY","BILLING","NOTIFICATION"})
    void terminalLocalRecordsNeverEstablishGlobalGuardClearance(Participant source){
        assertThat(guardCollector().collect(scope,Phase.REDACTION,source,peers.get(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isTrue();
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"RETURNS","PAYMENT","PROMOTION","DELIVERY","BILLING","NOTIFICATION"})
    void realSourceWorkBlocksWorkflowAndPreservesBusinessRows(Participant source){
        activateGuardSource(source);
        var step=new CommerceRetentionWorkflow(ds,manager).reviewSourceGuards(scope,Phase.REDACTION,source,peers.get(source)::guards);
        assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.BLOCKED);assertThat(step.activationAllowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD' AND state='BLOCKED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Long.class)).isEqualTo(1);
    }
    void activateGuardSource(Participant source){
        String mutation=switch(source){
            case RETURNS->"UPDATE return_requests SET status='UNDER_REVIEW'";
            case PAYMENT->"UPDATE payments SET processing_token='fixture-token'";
            case PROMOTION->"UPDATE coupon_reservations SET state='RESERVED'";
            case DELIVERY->"UPDATE shipments SET sync_state='PENDING'";
            case BILLING->"UPDATE event_inbox SET status='REJECTED'";
            case NOTIFICATION->"UPDATE notifications SET status='FAILED'";
            default->throw new AssertionError();};
        db.get(source).update(mutation);fresh();
    }
    @Test void completedReturnsRemainBlockedWithoutA16EffectsReconciliation(){
        db.get(Participant.RETURNS).update("UPDATE return_requests SET status='COMPLETED'");fresh();
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.RETURNS,peers.get(Participant.RETURNS)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
    }
    @Test void paymentDeadLetterFollowUpBlocksEvenWithTerminalPayment(){
        db.get(Participant.PAYMENT).update("INSERT INTO payment_follow_ups(order_id,kind,status,created_at,updated_at) VALUES(10,'INVOICE_ISSUE','DEAD_LETTER',now(),now())");fresh();
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.PAYMENT,peers.get(Participant.PAYMENT)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
    }
    @Test void openNotificationDlqBlocksEvenAfterNotificationWasSent(){
        db.get(Participant.NOTIFICATION).update("INSERT INTO notification_dead_letters(notification_id,event_type,source_id,recipient,channel,subject,message,status,failure_reason,attempt_count,created_at,updated_at) VALUES(1,'PAYMENT_FAILED',10,'fixture@example.test','EMAIL','Fixture','Fixture','OPEN','fixture',1,now(),now())");fresh();
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.NOTIFICATION,peers.get(Participant.NOTIFICATION)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
    }
    @Test void foreignNotificationKindsDoNotTurnChatIdsIntoOrderBlockers(){
        db.get(Participant.NOTIFICATION).update("INSERT INTO notifications(id,event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at) VALUES(2,'CHAT_MESSAGE',10,'fixture@example.test','EMAIL','Fixture','Fixture','FAILED',now(),now())");
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.NOTIFICATION,peers.get(Participant.NOTIFICATION)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
    }
    @Test void staleSourceAndRestoreQuarantineRetireRootRatherThanConfirmAbsence(){
        db.get(Participant.DELIVERY).update("UPDATE shipments SET sync_state='PENDING'");
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,peers.get(Participant.DELIVERY)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);
        fresh();db.get(Participant.DELIVERY).update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE");
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,peers.get(Participant.DELIVERY)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }
    @Test void rootChangeDuringRemoteReadDiscardsBoundObservationWithoutHoldingSqlLocks(){
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,c->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var reply=peers.get(Participant.DELIVERY).guards(c);
            jdbc.update("UPDATE orders SET customer_email='changed@fixture.test'");return reply;
        })).isEqualTo(CommerceRetentionSourceGuardCollector.Result.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }
    @Test void challengedReplyCannotBeReplayedAndPositiveReplyIsUnavailable(){
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,c->{var r=peers.get(Participant.DELIVERY).guards(c);return new PeerRetentionGuardProtocol.Reply(r.scope(),r.policy(),UUID.randomUUID(),r.code(),r.observedAt(),r.states());})).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,c->{var r=peers.get(Participant.DELIVERY).guards(c);var states=new EnumMap<Guard,State>(Guard.class);states.putAll(r.states());states.put(Guard.NO_ACTIVE_RETRY_OR_DLQ,State.CONFIRMED);return PeerRetentionGuardProtocol.reply(c,r.code(),r.observedAt(),states);})).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }
    @Test void unavailableSourceAndCallerTransactionNeverProduceProofs(){
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,c->{throw new IllegalStateException("fixture private content");})).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,peers.get(Participant.DELIVERY)::guards))).hasMessage("RETENTION_GUARD_CALLER_TRANSACTION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }
    @Test void holdArrivingDuringObservationBlocksPersistence(){
        assertThat(guardCollector().collect(scope,Phase.REDACTION,Participant.DELIVERY,c->{var r=peers.get(Participant.DELIVERY).guards(c);new CommerceRetentionHolds(ds,manager).placeCustomerPurchases(17,UUID.randomUUID(),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionHolds.Reason.OPEN_CASE);return r;})).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }
    CommerceRetentionPeerCollector.Result collect(Participant p){return collect(p,peers.get(p)::collect);}
    CommerceRetentionPeerCollector.Result collect(Participant p,CommerceRetentionPeerCollector.Transport transport){
        var diagnostics=new ArrayList<String>();CommerceRetentionPeerCollector.Result result=null;
        // UNAVAILABLE is a retriable observation with no stored proof. Keep a
        // bounded caller retry and report the actual source/clock failure.
        for(int attempt=0;attempt<3;attempt++){
            awaitSourceClock(p);result=collector.collect(scope,Phase.REDACTION,p,c->{
                Reply r=null;try{r=transport.collect(c);return PeerRetentionProtocol.validate(r,c);}
                catch(RuntimeException failure){diagnostics.add(p+":"+failure.getClass().getSimpleName()+":clock="+(r==null?null:r.reconciledAt())+":now="+java.time.Instant.now());throw failure;}
            });
            if(result!=CommerceRetentionPeerCollector.Result.UNAVAILABLE)break;
        }
        assertThat(result).withFailMessage("Source unavailable after bounded retries: %s",diagnostics).isNotEqualTo(CommerceRetentionPeerCollector.Result.UNAVAILABLE);return result;
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"RETURNS","PAYMENT","PROMOTION","DELIVERY","BILLING","NOTIFICATION"}) void allSixReadTheirOwnDurableClockAndExactOrderFacts(Participant p){if(p==Participant.BILLING || p==Participant.NOTIFICATION)assertThat(collect(Participant.PAYMENT)).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);assertThat(collect(p)).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE source=?",String.class,p.name())).isEqualTo("CONFIRMED");assertThat(collect(p)).isEqualTo(CommerceRetentionPeerCollector.Result.IDEMPOTENT);}
    @Test void billingAndNotificationsWaitForVerifiedPaymentReceipts(){for(var p:List.of(Participant.BILLING,Participant.NOTIFICATION))assertThat(collector.collect(scope,Phase.REDACTION,p,c->{throw new AssertionError("No HTTP before payment proof");})).isEqualTo(CommerceRetentionPeerCollector.Result.WAITING);}
    @Test void mismatchedCanonicalReceiptCannotBecomeConfirmed(){db.get(Participant.NOTIFICATION).update("UPDATE event_inbox SET event_id=?","payment-"+"f".repeat(64));fresh();assertThat(collect(Participant.PAYMENT)).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);assertThat(collect(Participant.NOTIFICATION)).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE source='NOTIFICATION'",String.class)).isEqualTo("UNKNOWN");}
    @Test void activeDeliveryAndCompletedUnreconciledReturnStayBlockedOrUnknown(){db.get(Participant.DELIVERY).update("UPDATE shipments SET status='IN_TRANSIT',sync_state='PENDING'");db.get(Participant.RETURNS).update("UPDATE return_requests SET status='COMPLETED'");fresh();collect(Participant.DELIVERY);collect(Participant.RETURNS);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE source='DELIVERY'",String.class)).isEqualTo("BLOCKED");assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE source='RETURNS'",String.class)).isEqualTo("UNKNOWN");}
    @Test void externalStripePaymentRemainsProtected(){db.get(Participant.PAYMENT).update("UPDATE payments SET checkout_session_id='cs_test_fixture'");fresh();collect(Participant.PAYMENT);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE source='PAYMENT'",String.class)).isEqualTo("UNKNOWN");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_payment_evidence",Long.class)).isZero();}
    @Test void mismatchedDeclaredRefundOrderCannotHideItsCanonicalPaymentParent(){db.get(Participant.PAYMENT).update("INSERT INTO payment_refunds(payment_id,order_id,request_key,operation_id,amount,currency,intent_id,status,automatic,reason,requested_by,created_at,updated_at) VALUES(1,11,'fixture-refund','fixture-operation',10,'PLN','fixture-intent','REQUESTED',TRUE,'Fixture','fixture',now(),now())");fresh();assertThat(collector.collect(scope,Phase.REDACTION,Participant.PAYMENT,c->{var reply=peers.get(Participant.PAYMENT).collect(c);assertThat(reply.code()).isEqualTo(Code.PROVIDER_REVIEW_REQUIRED);return reply;})).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_evidence WHERE source='PAYMENT'",String.class)).isEqualTo("UNKNOWN");}
    @Test void clockCannotBeForgedOrReusedAfterRestore(){for(var p:PEERS){var q=db.get(p);var original=q.queryForObject("SELECT local_reconciled_at FROM commerce_retention_source_state",java.sql.Timestamp.class);assertThat(original).isNotNull();assertThatThrownBy(()->q.update("UPDATE commerce_retention_source_state SET local_reconciled_at=now()-interval '1000 days'")).isInstanceOf(org.springframework.dao.DataAccessException.class);q.update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE,source_revision=source_revision+1");assertThat(q.queryForObject("SELECT local_reconciled_at FROM commerce_retention_source_state",java.sql.Timestamp.class)).isEqualTo(original);assertThatThrownBy(()->q.update("UPDATE commerce_retention_source_state SET restore_blocked=FALSE")).isInstanceOf(org.springframework.dao.DataAccessException.class);}assertThat(collect(Participant.PAYMENT)).isEqualTo(CommerceRetentionPeerCollector.Result.STALE);}
    @Test void rollbackAndNewBindingCannotShiftSourceClocks(){var q=db.get(Participant.DELIVERY);var original=q.queryForObject("SELECT local_reconciled_at FROM commerce_retention_source_state",java.sql.Timestamp.class);new TransactionTemplate(managers.get(Participant.DELIVERY)).executeWithoutResult(tx->{q.update("UPDATE shipments SET status='IN_TRANSIT',sync_state='PENDING'");tx.setRollbackOnly();});fresh();assertThat(q.queryForObject("SELECT local_reconciled_at FROM commerce_retention_source_state",java.sql.Timestamp.class)).isEqualTo(original);assertThat(collect(Participant.DELIVERY)).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);}
    @Test void orderMutationDuringRemoteReadCannotWriteAProof(){assertThat(collector.collect(scope,Phase.REDACTION,Participant.DELIVERY,c->{var reply=peers.get(Participant.DELIVERY).collect(c);jdbc.update("UPDATE orders SET version=version+1");return reply;})).isEqualTo(CommerceRetentionPeerCollector.Result.STALE);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();}
    @Test void allSixStillCannotAuthorizeErasureWithoutGlobalGuards(){collect(Participant.PAYMENT);for(var p:PEERS)if(p!=Participant.PAYMENT)collect(p);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE state='CONFIRMED'",Long.class)).isEqualTo(6);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD'",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Long.class)).isEqualTo(1);}
    @Test void historicalTerminalRowsDoNotAcquireClocksAtBinding(){for(var p:PEERS){db.get(p).execute("TRUNCATE commerce_retention_source_state CASCADE");var s=new Subject(10,17,0,UUID.randomUUID());var b=outboxes.get(p).bind(s,Phase.REDACTION,0);var hash=(p==Participant.BILLING || p==Participant.NOTIFICATION)?eventHash(List.of(EVENT)):null;var f=new Facts(p==Participant.RETURNS || p==Participant.BILLING?17:0,"CANCELLED","10.00","10.00","0.00","FIXTURE","TRACK","CANCELLED",3,false,hash,hash==null?0:1);var c=new Command(new Scope(p,10,s.chainGeneration(),0,Phase.REDACTION,b.epoch(),b.revision()),f);assertThat(peers.get(p).collect(c).code()).as(p.name()).isEqualTo(Code.MISSING_MILESTONE);}}
    java.time.Instant cacheForRevalidation(){
        new CommerceRetentionOrderEvidence(ds,manager).collect(scope,Phase.REDACTION);
        var time=java.time.Instant.now().minus(2,java.time.temporal.ChronoUnit.DAYS).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(new CommerceRetentionInventoryCollector(ds,manager).collect(scope,Phase.REDACTION,c->InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,time))).isEqualTo(CommerceRetentionInventoryCollector.Result.STORED);
        collect(Participant.PAYMENT);for(var p:PEERS)if(p!=Participant.PAYMENT)collect(p);
        jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',code='AGE_REACHED',next_attempt_at=clock_timestamp()+interval '1 day' WHERE current");return time;
    }
    @ParameterizedTest @EnumSource(value=Participant.class,names={"RETURNS","PAYMENT","PROMOTION","DELIVERY","BILLING","NOTIFICATION"})
    void fullRoundFindsUndeliveredBusinessInvalidationInEveryRealPeer(Participant changed){
        var time=cacheForRevalidation();String mutation=switch(changed){
            case RETURNS->"UPDATE return_requests SET description='Changed fixture'";
            case PAYMENT->"UPDATE payments SET result_recorded_at=clock_timestamp()";
            case PROMOTION->"UPDATE coupon_reservations SET discount_amount=1";
            case DELIVERY->"UPDATE shipments SET sync_state='PENDING'";
            case BILLING->"UPDATE invoices SET buyer_name='Changed fixture'";
            case NOTIFICATION->"UPDATE notifications SET message='Changed fixture'";
            default->throw new AssertionError();};
        db.get(changed).update(mutation);assertThat(db.get(changed).queryForObject("SELECT invalidated FROM commerce_retention_source_state",Boolean.class)).isTrue();
        var step=new CommerceRetentionWorkflow(ds,manager).revalidateAndAssess(scope,Phase.REDACTION,c->InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,time),c->{awaitSourceClock(c.scope().source());return peers.get(c.scope().source()).collect(c);});
        assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.STALE);assertThat(step.assessment()).isNull();assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isEqualTo(8);
    }
    @Test void sequentialRevalidationStillDoesNotFreezeAnAlreadyReadSource(){
        var time=cacheForRevalidation();var step=new CommerceRetentionWorkflow(ds,manager).revalidateAndAssess(scope,Phase.REDACTION,c->InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,time),c->{
            if(c.scope().source()==Participant.BILLING)db.get(Participant.DELIVERY).update("UPDATE shipments SET sync_state='PENDING'");
            awaitSourceClock(c.scope().source());return peers.get(c.scope().source()).collect(c);
        });
        assertThat(db.get(Participant.DELIVERY).queryForObject("SELECT invalidated FROM commerce_retention_source_state",Boolean.class)).isTrue();assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.REVALIDATED_ASSESSED);assertThat(step.activationAllowed()).isFalse();assertThat(step.assessment().activationAllowed()).isFalse();
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"assessed","stop","lost-prepare-reply"})
    void intentBoundSourceSqlFencesReleaseAfterAssessmentStopOrLostReply(String outcome){
        var time=cacheForRevalidation();var workflow=new CommerceRetentionWorkflow(ds,manager);var intentId=UUID.randomUUID();
        assertThat(workflow.prepareIntent(scope,Phase.REDACTION,intentId).code()).isEqualTo(CommerceRetentionIntents.Code.PREPARED);
        var inventoryUntil=java.time.Instant.now().plusSeconds(15);
        var step=workflow.fenceIntentAndAssess(scope,Phase.REDACTION,intentId,0,
                c->InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,time),
                c->{awaitSourceClock(c.scope().source());return peers.get(c.scope().source()).collect(c);},
                c->{
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if(c.scope().source()==Participant.INVENTORY) return PeerRetentionFenceProtocol.reply(c,
                        c.action()==PeerRetentionFenceProtocol.Action.RELEASE?PeerRetentionFenceProtocol.Status.RELEASED:PeerRetentionFenceProtocol.Status.FENCED,
                        c.action()==PeerRetentionFenceProtocol.Action.RELEASE?null:java.time.Instant.now(),
                        c.action()==PeerRetentionFenceProtocol.Action.RELEASE?null:inventoryUntil);
                    if(c.action()==PeerRetentionFenceProtocol.Action.VERIFY && c.scope().source()==Participant.DELIVERY)
                        assertThatThrownBy(()->db.get(Participant.DELIVERY).update("UPDATE shipments SET sync_state='PENDING'"))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    var reply=peers.get(c.scope().source()).fence(c);
                    if(outcome.equals("lost-prepare-reply") && c.action()==PeerRetentionFenceProtocol.Action.PREPARE && c.scope().source()==Participant.PAYMENT)
                        throw new IllegalStateException("fixture source committed; reply lost");
                    if(outcome.equals("stop") && c.action()==PeerRetentionFenceProtocol.Action.VERIFY && c.scope().source()==Participant.DELIVERY)
                        workflow.requestIntentStop(scope,Phase.REDACTION,intentId,0,UUID.randomUUID());
                    return reply;
                });
        assertThat(db.get(Participant.DELIVERY).queryForObject("SELECT invalidated FROM commerce_retention_source_state",Boolean.class)).isFalse();
        assertThat(step.review().code()).isEqualTo(switch(outcome){case "assessed"->CommerceRetentionWorkflow.Code.INTENT_FENCED_ASSESSED;case "stop"->CommerceRetentionWorkflow.Code.INTENT_STOPPED;default->CommerceRetentionWorkflow.Code.UNAVAILABLE;});
        assertThat(step.cleanup()).isEqualTo(CommerceRetentionWorkflow.Cleanup.RELEASED);
        for(var p:PEERS)assertThat(db.get(p).queryForObject("SELECT count(*) FROM commerce_retention_source_state WHERE retention_fence_token IS NOT NULL",Long.class)).isZero();
        if(!outcome.equals("assessed"))assertThat(step.review().assessment()).isNull();
        assertThat(step.activationAllowed()).isFalse();
        assertThat(db.get(Participant.DELIVERY).update("UPDATE shipments SET sync_state='PENDING'")).isOne();
    }

    PeerRetentionFenceProtocol.Command fenceCommand(Participant p, UUID token, PeerRetentionFenceProtocol.Action action) {
        var r=db.get(p).queryForMap("SELECT binding_epoch,source_revision FROM commerce_retention_source_state WHERE order_id=10");
        return new PeerRetentionFenceProtocol.Command(action,new PeerRetentionFenceProtocol.Scope(p,10,scope.chainGeneration(),scope.orderVersion(),Phase.REDACTION,
            ((Number)r.get("binding_epoch")).longValue(),((Number)r.get("source_revision")).longValue(),token));
    }

    @ParameterizedTest @EnumSource(value=Participant.class,names={"RETURNS","PAYMENT","PROMOTION","DELIVERY","BILLING","NOTIFICATION"})
    void durableFenceRejectsReplicaConflictAndBusinessWrites(Participant p) {
        var token=UUID.randomUUID(); var command=fenceCommand(p,token,PeerRetentionFenceProtocol.Action.PREPARE);
        var prepared=peers.get(p).fence(command);
        assertThat(prepared.status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        var replica=new PeerRetentionEvidence(p,db.get(p).getDataSource(),managers.get(p));
        assertThat(replica.fence(fenceCommand(p,UUID.randomUUID(),PeerRetentionFenceProtocol.Action.PREPARE)).status())
            .isEqualTo(PeerRetentionFenceProtocol.Status.CONFLICT);
        assertThat(replica.fence(fenceCommand(p,token,PeerRetentionFenceProtocol.Action.VERIFY)).status())
            .isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        String sql=switch(p){
            case RETURNS->"UPDATE return_requests SET description='Changed fixture'";
            case PAYMENT->"UPDATE payments SET result_recorded_at=clock_timestamp()";
            case PROMOTION->"UPDATE coupon_reservations SET discount_amount=1";
            case DELIVERY->"UPDATE shipments SET sync_state='PENDING'";
            case BILLING->"UPDATE invoices SET buyer_name='Changed fixture'";
            case NOTIFICATION->"UPDATE notifications SET message='Changed fixture'";
            default->throw new AssertionError();};
        assertThatThrownBy(()->db.get(p).update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(peers.get(p).fence(fenceCommand(p,token,PeerRetentionFenceProtocol.Action.PREPARE)).expiresAt()).isEqualTo(prepared.expiresAt());
        assertThat(replica.fence(fenceCommand(p,token,PeerRetentionFenceProtocol.Action.RELEASE)).status()).isEqualTo(PeerRetentionFenceProtocol.Status.RELEASED);
        assertThat(db.get(p).update(sql)).isGreaterThan(0);
    }

    @Test void expiredLeaseCannotBeRevivedByOldPrepareOrBlockBusinessForever() {
        var p=Participant.DELIVERY;var token=UUID.randomUUID();
        assertThat(peers.get(p).fence(fenceCommand(p,token,PeerRetentionFenceProtocol.Action.PREPARE)).status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        db.get(p).update("UPDATE commerce_retention_source_state SET retention_fence_until=clock_timestamp()-interval '1 second'");
        assertThat(peers.get(p).fence(fenceCommand(p,token,PeerRetentionFenceProtocol.Action.PREPARE)).status()).isEqualTo(PeerRetentionFenceProtocol.Status.STALE);
        assertThat(db.get(p).update("UPDATE shipments SET sync_state='PENDING'")).isOne();
    }

    @Test void fenceCrashRecoveryAndReplayScenarios(){
        var p=Participant.DELIVERY;
        long epoch=db.get(p).queryForObject("SELECT binding_epoch FROM commerce_retention_source_state",Long.class);
        long revision=db.get(p).queryForObject("SELECT source_revision FROM commerce_retention_source_state",Long.class);
        UUID token1=UUID.randomUUID();UUID token2=UUID.randomUUID();
        var cmd1=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.PREPARE,
                new PeerRetentionFenceProtocol.Scope(p,scope.orderId(),scope.chainGeneration(),scope.orderVersion(),Phase.REDACTION,epoch,revision,token1));
        var r1=peers.get(p).fence(cmd1);
        assertThat(r1.status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        var r1Replay=peers.get(p).fence(cmd1);
        assertThat(r1Replay.status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        var cmd2=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.PREPARE,
                new PeerRetentionFenceProtocol.Scope(p,scope.orderId(),scope.chainGeneration(),scope.orderVersion(),Phase.REDACTION,epoch,revision,token2));
        var r2Conflict=peers.get(p).fence(cmd2);
        assertThat(r2Conflict.status()).isEqualTo(PeerRetentionFenceProtocol.Status.CONFLICT);
        var rel=peers.get(p).fence(new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,
                new PeerRetentionFenceProtocol.Scope(p,scope.orderId(),scope.chainGeneration(),scope.orderVersion(),Phase.REDACTION,epoch,revision,token1)));
        assertThat(rel.status()).isEqualTo(PeerRetentionFenceProtocol.Status.RELEASED);
        var r2Success=peers.get(p).fence(cmd2);
        assertThat(r2Success.status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        peers.get(p).fence(new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,
                new PeerRetentionFenceProtocol.Scope(p,scope.orderId(),scope.chainGeneration(),scope.orderVersion(),Phase.REDACTION,epoch,revision,token2)));
    }
    @Test void guardHttpRoundAcrossSixSourcesNeverEstablishesGlobalClearance()throws Exception{
        try(var http=new RetentionGuardHttpFixture(peers)){
            var workflow=new CommerceRetentionWorkflow(ds,manager);
            for(var source:PEERS){
                var step=workflow.reviewSourceGuards(scope,Phase.REDACTION,source,c->{
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return http.client(source).guards(c);
                });
                assertThat(step.code()).as(source.name()).isEqualTo(CommerceRetentionWorkflow.Code.WAITING_DEPENDENCY);
                assertThat(step.activationAllowed()).isFalse();assertThat(http.commands(source)).hasSize(1);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isTrue();
        }
    }

    @Test void actualGuardHttpAuthenticationRejectsSpoofedRolesAndGetAcrossAllSources()throws Exception{
        try(var http=new RetentionGuardHttpFixture(peers)){
            for(var source:PEERS){
                for(String token:List.of("customer","operator","other")){
                    var denied=http.request(source,token,"POST","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    assertThat(denied.statusCode()).as(source+":"+token).isEqualTo(403);
                    assertThat(denied.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
                }
                assertThat(http.request(source,null,"POST",new byte[0]).statusCode()).isEqualTo(401);
                assertThat(http.request(source,"order","GET",new byte[0]).statusCode()).isEqualTo(403);
                assertThat(http.request(source,"order","POST",new byte[2049]).statusCode()).isEqualTo(413);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        }
    }

    @ParameterizedTest @EnumSource(value=Participant.class,names={"RETURNS","PAYMENT","PROMOTION","DELIVERY","BILLING","NOTIFICATION"})
    void sourceBlockerTraversesRealHttpAndRetiresWorkflowGeneration(Participant source)throws Exception{
        activateGuardSource(source);
        try(var http=new RetentionGuardHttpFixture(peers)){
            var step=new CommerceRetentionWorkflow(ds,manager).reviewSourceGuards(scope,Phase.REDACTION,source,http.client(source)::guards);
            assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.BLOCKED);
            assertThat(step.activationAllowed()).isFalse();assertThat(http.commands(source)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD' AND state='BLOCKED'",Long.class)).isEqualTo(1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"503","oversized","malformed"})
    void guardHttpFailureHasNoProofOrAutomaticRetryAndExplicitRetryUsesFreshChallenge(String failure)throws Exception{
        var source=Participant.DELIVERY;
        try(var http=new RetentionGuardHttpFixture(peers)){
            http.transforms.put(source,wire->switch(failure){
                case "503"->new RetentionGuardHttpFixture.Wire(503,"fixture-private-error".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                case "oversized"->new RetentionGuardHttpFixture.Wire(200,new byte[2049]);
                default->new RetentionGuardHttpFixture.Wire(200,"fixture-private-error".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            var workflow=new CommerceRetentionWorkflow(ds,manager);
            assertThat(workflow.reviewSourceGuards(scope,Phase.REDACTION,source,http.client(source)::guards).code()).isEqualTo(CommerceRetentionWorkflow.Code.UNAVAILABLE);
            assertThat(http.commands(source)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,scope.chainGeneration())).isZero();
            assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_jobs WHERE generation=?",Integer.class,scope.chainGeneration())).isZero();
            http.transforms.clear();
            assertThat(workflow.reviewSourceGuards(scope,Phase.REDACTION,source,http.client(source)::guards).code()).isEqualTo(CommerceRetentionWorkflow.Code.WAITING_DEPENDENCY);
            assertThat(http.commands(source)).hasSize(2);
            assertThat(http.commands(source).stream().map(PeerRetentionGuardProtocol.Command::challenge).distinct().count()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        }
    }

    @Test void previousGuardHttpResponseCannotBeReplayedForANewRequest()throws Exception{
        var source=Participant.DELIVERY;var saved=new java.util.concurrent.atomic.AtomicReference<RetentionGuardHttpFixture.Wire>();
        try(var http=new RetentionGuardHttpFixture(peers)){
            http.transforms.put(source,wire->{saved.set(wire);return wire;});
            assertThat(guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
            http.transforms.put(source,wire->saved.get());
            assertThat(guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);
            http.transforms.clear();
            assertThat(guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
            assertThat(http.commands(source).stream().map(PeerRetentionGuardProtocol.Command::challenge).distinct().count()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        }
    }

    @Test void localMutationDuringGuardHttpCallDiscardsTheResponse()throws Exception{
        try(var http=new RetentionGuardHttpFixture(peers)){
            http.beforeDispatch=source->jdbc.update("UPDATE orders SET customer_email='changed@fixture.test' WHERE id=10");
            var step=new CommerceRetentionWorkflow(ds,manager).reviewSourceGuards(scope,Phase.REDACTION,Participant.DELIVERY,http.client(Participant.DELIVERY)::guards);
            assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.STALE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        }
    }

    @Test void sourceChangeAfterGuardReadRemainsUnknownAndNextReadFindsStaleBinding()throws Exception{
        var source=Participant.DELIVERY;
        try(var http=new RetentionGuardHttpFixture(peers)){
            http.afterDispatch=p->db.get(p).update("UPDATE shipments SET sync_state='PENDING' WHERE order_id=10");
            var workflow=new CommerceRetentionWorkflow(ds,manager);
            var first=workflow.reviewSourceGuards(scope,Phase.REDACTION,source,http.client(source)::guards);
            assertThat(first.code()).isEqualTo(CommerceRetentionWorkflow.Code.WAITING_DEPENDENCY);assertThat(first.activationAllowed()).isFalse();
            http.afterDispatch=p->{};
            assertThat(workflow.reviewSourceGuards(scope,Phase.REDACTION,source,http.client(source)::guards).code()).isEqualTo(CommerceRetentionWorkflow.Code.STALE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void concurrentGuardHttpReadsCannotOverwriteAProofAndBlockerRetriesAreIdempotent(boolean blocked)throws Exception{
        var source=blocked?Participant.PAYMENT:Participant.DELIVERY;if(blocked)activateGuardSource(source);
        var arrived=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        try(var http=new RetentionGuardHttpFixture(peers);var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            http.afterDispatch=p->{arrived.countDown();awaitGuardLatch(release);};
            var a=pool.submit(()->guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards));
            var b=pool.submit(()->guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards));
            try{
                assertThat(arrived.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();release.countDown();
                var results=List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));
                if(blocked)assertThat(results).containsExactlyInAnyOrder(CommerceRetentionSourceGuardCollector.Result.BLOCKED,CommerceRetentionSourceGuardCollector.Result.STALE);
                else assertThat(results).containsOnly(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
            }finally{release.countDown();}
            assertThat(http.commands(source).stream().map(PeerRetentionGuardProtocol.Command::challenge).distinct().count()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD'",Long.class)).isEqualTo(blocked?1:0);
            if(blocked){
                http.afterDispatch=p->{};
                long revision=jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,scope.chainGeneration());
                assertThat(guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.BLOCKED);
                assertThat(jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,scope.chainGeneration())).isEqualTo(revision);
            }
        }finally{release.countDown();}
    }
    private static void awaitGuardLatch(java.util.concurrent.CountDownLatch latch){
        try{if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture guard synchronization timeout");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }

    @Test void sourceSqlFailureBecomesHttpUnavailableAndRecoveryDoesNotNeedANewGeneration()throws Exception{
        var source=Participant.DELIVERY;var local=db.get(source);
        try(var http=new RetentionGuardHttpFixture(peers)){
            local.execute("ALTER TABLE shipments RENAME TO fixture_unavailable_shipments");
            try{assertThat(guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);}
            finally{local.execute("ALTER TABLE fixture_unavailable_shipments RENAME TO shipments");}
            assertThat(guardCollector().collect(scope,Phase.REDACTION,source,http.client(source)::guards)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        }
    }

    @Test void localConnectionFailureDoesNotCallTheRemoteSource()throws Exception{
        var unavailable=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        org.mockito.Mockito.when(unavailable.getConnection()).thenThrow(new java.sql.SQLException("fixture-private-connection-error"));
        var remote=org.mockito.Mockito.mock(CommerceRetentionSourceGuardCollector.Source.class);
        var collector=new CommerceRetentionSourceGuardCollector(unavailable,new DataSourceTransactionManager(unavailable));
        assertThat(collector.collect(scope,Phase.REDACTION,Participant.DELIVERY,remote)).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);
        var workflow=new CommerceRetentionWorkflow(unavailable,new DataSourceTransactionManager(unavailable));
        assertThat(workflow.reviewSourceGuards(scope,Phase.REDACTION,Participant.DELIVERY,remote).code()).isEqualTo(CommerceRetentionWorkflow.Code.UNAVAILABLE);
        org.mockito.Mockito.verifyNoInteractions(remote);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lostCommitAcknowledgementIsUnavailableAndRetryRecognizesTheCommittedObservation(boolean blocked)throws Exception{
        var source=blocked?Participant.PAYMENT:Participant.DELIVERY;if(blocked)activateGuardSource(source);
        var loseCommit=new java.util.concurrent.atomic.AtomicBoolean();
        var uncertain=new DelegatingDataSource(ds){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                var delegate=super.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    try{
                        var result=method.invoke(delegate,args);
                        if(method.getName().equals("commit") && loseCommit.compareAndSet(true,false))throw new java.sql.SQLException("fixture-lost-commit-acknowledgement");
                        return result;
                    }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
        var collector=new CommerceRetentionSourceGuardCollector(uncertain,new DataSourceTransactionManager(uncertain));
        try(var http=new RetentionGuardHttpFixture(peers)){
            var first=collector.collect(scope,Phase.REDACTION,source,c->{var reply=http.client(source).guards(c);loseCommit.set(true);return reply;});
            assertThat(first).isEqualTo(CommerceRetentionSourceGuardCollector.Result.UNAVAILABLE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD' AND state='BLOCKED'",Long.class)).isEqualTo(blocked?1:0);
            var receipt=jdbc.queryForMap("SELECT * FROM commerce_retention_source_receipts WHERE generation=? AND source=?",scope.chainGeneration(),source.name());
            long revision=jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,scope.chainGeneration());
            assertThat(collector.collect(scope,Phase.REDACTION,source,http.client(source)::guards)).isEqualTo(blocked?CommerceRetentionSourceGuardCollector.Result.BLOCKED:CommerceRetentionSourceGuardCollector.Result.WAITING_PROOF);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence WHERE kind='GUARD'",Long.class)).isEqualTo(blocked?1:0);
            assertThat(jdbc.queryForMap("SELECT * FROM commerce_retention_source_receipts WHERE generation=? AND source=?",scope.chainGeneration(),source.name())).isEqualTo(receipt);
            assertThat(jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,scope.chainGeneration())).isEqualTo(revision);
            assertThat(http.commands(source).stream().map(PeerRetentionGuardProtocol.Command::challenge).distinct().count()).isEqualTo(2);
        }
    }

    @Test void localSqlFailureRollsBackInsertedGuardAndLeavesGenerationRetryable()throws Exception{
        var source=Participant.PAYMENT;activateGuardSource(source);
        jdbc.execute("CREATE FUNCTION fixture_guard_write_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture-private-error' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER fixture_guard_write_failure BEFORE UPDATE ON commerce_retention_jobs FOR EACH ROW WHEN (NEW.proof_revision>OLD.proof_revision) EXECUTE FUNCTION fixture_guard_write_failure()");
        try(var http=new RetentionGuardHttpFixture(peers)){
            try{
                var step=new CommerceRetentionWorkflow(ds,manager).reviewSourceGuards(scope,Phase.REDACTION,source,http.client(source)::guards);
                assertThat(step.code()).isEqualTo(CommerceRetentionWorkflow.Code.UNAVAILABLE);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_receipts",Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT proof_revision FROM commerce_retention_jobs WHERE generation=?",Long.class,scope.chainGeneration())).isZero();
                assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,scope.chainGeneration())).isTrue();
            }finally{
                jdbc.execute("DROP TRIGGER fixture_guard_write_failure ON commerce_retention_jobs");jdbc.execute("DROP FUNCTION fixture_guard_write_failure()");
            }
            assertThat(new CommerceRetentionWorkflow(ds,manager).reviewSourceGuards(scope,Phase.REDACTION,source,http.client(source)::guards).code()).isEqualTo(CommerceRetentionWorkflow.Code.BLOCKED);
        }
    }

    @Test void realHttpFilterAndSourceControllerServeAllSixPeersOnlyToOrder()throws Exception{
        var servers=new ArrayList<com.sun.net.httpserver.HttpServer>();var json=tools.jackson.databind.json.JsonMapper.builder().build();
        try{
            var issuer=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);servers.add(issuer);issuer.createContext("/internal/auth/introspect",e->{String token=json.readTree(e.getRequestBody().readAllBytes()).path("token").asText();var actor=switch(token){case "order"->new org.demo.platformsecurity.Actor("SERVICE","order-service",null,null);case "customer"->new org.demo.platformsecurity.Actor("CUSTOMER",null,17L,null);case "operator"->new org.demo.platformsecurity.Actor("OPERATOR",null,null,null);default->new org.demo.platformsecurity.Actor("SERVICE","inventory-service",null,null);};byte[] raw=json.writeValueAsBytes(actor);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});issuer.start();
            var clients=new EnumMap<Participant,PeerRetentionClient>(Participant.class);var httpObservations=new java.util.concurrent.ConcurrentHashMap<Participant,String>();
            for(var p:PEERS){String name=p.name().toLowerCase(Locale.ROOT)+"-service";org.springframework.boot.web.servlet.FilterRegistrationBean<?> reg=org.springframework.test.util.ReflectionTestUtils.invokeMethod(new org.demo.platformsecurity.PlatformSecurityConfiguration(),"platformAuthenticationFilter",name,"http://127.0.0.1:"+issuer.getAddress().getPort(),"fixture-"+name);
                var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new PeerRetentionConfiguration.Controller(peers.get(p))).setHandlerExceptionResolvers(new org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver(),new org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver()).addFilters(reg.getFilter()).build();
                for(String token:List.of("customer","operator","other"))assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PeerRetentionProtocol.PATH).header("Authorization","Bearer "+token).header("X-Service-Name","order-service").contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
                assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PeerRetentionProtocol.PATH).header("Authorization","Bearer order").contentType("application/json").content("x".repeat(8193))).andReturn().getResponse().getStatus()).isEqualTo(413);
                var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);servers.add(server);server.createContext(PeerRetentionProtocol.PATH,e->{try{var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PeerRetentionProtocol.PATH).header("Authorization",e.getRequestHeaders().getFirst("Authorization")).contentType("application/json").content(e.getRequestBody().readNBytes(8193))).andReturn().getResponse();byte[] raw=response.getContentAsByteArray();var observed=json.readTree(raw);httpObservations.put(p,"status="+response.getStatus()+":code="+observed.path("code").asText()+":clock="+observed.path("reconciledAt").asText()+":JVM="+java.time.Instant.now());e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(response.getStatus(),raw.length==0?-1:raw.length);if(raw.length>0)e.getResponseBody().write(raw);}catch(Exception failure){throw new java.io.IOException("Fixture HTTP failed",failure);}finally{e.close();}});server.start();clients.put(p,new PeerRetentionClient("http://127.0.0.1:"+server.getAddress().getPort(),new org.demo.platformsecurity.ServiceAuthenticationInterceptor("order")));
            }
            assertThat(collect(Participant.PAYMENT,clients.get(Participant.PAYMENT)::collect)).as("Payment HTTP %s",httpObservations).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);
            for(var p:PEERS)if(p!=Participant.PAYMENT)assertThat(collect(p,clients.get(p)::collect)).as("%s HTTP %s",p,httpObservations).isEqualTo(CommerceRetentionPeerCollector.Result.STORED);
            new CommerceRetentionOrderEvidence(ds,manager).collect(scope,Phase.REDACTION);
            var inventoryTime=java.time.Instant.now().minus(2,java.time.temporal.ChronoUnit.DAYS).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            new CommerceRetentionInventoryCollector(ds,manager).collect(scope,Phase.REDACTION,c->InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,inventoryTime));
            jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day' WHERE current");
            var round=new CommerceRetentionWorkflow(ds,manager).revalidateAndAssess(scope,Phase.REDACTION,c->InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,inventoryTime),c->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return clients.get(c.scope().source()).collect(c);});
            assertThat(round.code()).as("Full authenticated HTTP round %s",httpObservations).isEqualTo(CommerceRetentionWorkflow.Code.REVALIDATED_ASSESSED);assertThat(round.activationAllowed()).isFalse();
            jdbc.update("UPDATE commerce_retention_jobs SET next_attempt_at=clock_timestamp()-interval '1 day' WHERE current");
            var workflow=new CommerceRetentionWorkflow(ds,manager);var intentId=UUID.randomUUID();
            assertThat(workflow.prepareIntent(scope,Phase.REDACTION,intentId).code()).isEqualTo(CommerceRetentionIntents.Code.PREPARED);
            var intentRound=workflow.revalidateIntentAndAssess(scope,Phase.REDACTION,intentId,0,
                c->InventoryRetentionProtocol.reply(c,State.CONFIRMED,InventoryRetentionProtocol.Code.OBSERVED,inventoryTime),
                c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return clients.get(c.scope().source()).collect(c);});
            assertThat(intentRound.code()).as("Intent-bound authenticated HTTP round %s",httpObservations).isEqualTo(CommerceRetentionWorkflow.Code.INTENT_REVALIDATED_ASSESSED);
            assertThat(intentRound.observations()).hasSize(8);assertThat(intentRound.activationAllowed()).isFalse();assertThat(intentRound.assessment()).isNotNull();
            assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_intents WHERE intent_id=?",String.class,intentId)).isEqualTo("WAITING_AUTHORIZATION");
        }finally{servers.forEach(s->s.stop(0));}
    }
}
