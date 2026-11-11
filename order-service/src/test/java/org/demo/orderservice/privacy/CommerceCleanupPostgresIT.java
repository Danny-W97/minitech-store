package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CommerceCleanupPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("commerce_fixture").withUsername("fixture").withPassword("fixture-only");
    static JdbcTemplate jdbc;static PrivacyFenceStore fences;static TransactionTemplate tx;
    final UUID request=UUID.randomUUID();boolean finance=true;
    CommerceOrderSource source=(customer,id,cursor)->cursor<10?Optional.of(new CommerceOrderSource.Entry(10,"ELIGIBLE",finance,"COMPLETED",20,true,true)):Optional.empty();
    @BeforeAll static void setup(){var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);fences=new PrivacyFenceStore(ds,manager);tx=new TransactionTemplate(manager);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE finance_privacy_consents,order_items,order_sagas,orders,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");}
    void fence(){fences.fence(17,request);}
    void denied(String sql){assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    long order(long owner){return jdbc.queryForObject("""
        INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at)
        VALUES (10,?,'owner@fixture.example',123.45,'CANCELLED','Jan','Test','Testowa','00-001','Warszawa','PL','123456789','STANDARD_COURIER',12.34,0,'HOME_DELIVERY','retained-key',repeat('b',64),'customer:17',now(),now()) RETURNING id
        """,Long.class,owner);}
    void seed(){order(17);jdbc.update("INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES(10,'FAILED',now(),now())");jdbc.update("INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) VALUES(10,1,'SKU','Product',2,61.725,123.45)");}
    OrderPrivacySource allowed(){return new OrderPrivacySource(fences,(db,id,version)->true,true);}
    @Test void actualFinanceGateNeverClaimsClearanceAndDisabledSourceNeverFreezes(){seed();fence();assertThat(new OrderPrivacySource(fences,new PendingCommerceFinanceGate(),true).next(17,request,0).orElseThrow().financeCleared()).isFalse();assertThat(new OrderPrivacySource(fences,(db,id,v)->true,false).next(17,request,0).orElseThrow().financeCleared()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();}
    @Test void redactionPreservesMoneyKeysLinesAndRejectsLateRestoration(){seed();fence();var adapter=new OrderCommerceCleanup(fences,allowed());var batch=adapter.cleanup(17,request,0);assertThat(batch.nextCursor()).isEqualTo(10);assertThat(jdbc.queryForMap("SELECT privacy_redacted,customer_email,delivery_street,delivery_phone,idempotency_key FROM orders WHERE id=10")).containsEntry("privacy_redacted",true).containsEntry("customer_email",null).containsEntry("delivery_street",null).containsEntry("delivery_phone",null).containsEntry("idempotency_key","retained-key");assertThat(jdbc.queryForObject("SELECT total_price FROM orders",java.math.BigDecimal.class)).isEqualByComparingTo("123.45");assertThat(jdbc.queryForObject("SELECT quantity FROM order_items",Integer.class)).isEqualTo(2);assertThat(adapter.cleanup(17,request,0).nextCursor()).isEqualTo(10);assertThat(adapter.cleanup(17,request,10).outcome()).isEqualTo("END");denied("UPDATE orders SET customer_email='restored@fixture.example' WHERE id=10");denied("UPDATE orders SET status='PAID' WHERE id=10");denied("UPDATE order_items SET quantity=3 WHERE order_id=10");denied("DELETE FROM order_sagas WHERE order_id=10");jdbc.update("UPDATE order_sagas SET last_error=NULL WHERE order_id=10");}
    @Test void activeAndUnreconciledOrUnownedOrdersCannotRedact(){seed();denied("UPDATE orders SET customer_email=NULL WHERE id=10");jdbc.update("UPDATE orders SET status='PAID'");fence();var adapter=new OrderCommerceCleanup(fences,allowed());assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_ORDER_PROCESS");jdbc.update("UPDATE orders SET status='CANCELLED',lifecycle_review_required=true");assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("ORDER_REVIEW_REQUIRED");assertThat(jdbc.queryForObject("SELECT customer_email FROM orders",String.class)).isEqualTo("owner@fixture.example");assertThatThrownBy(()->adapter.cleanup(18,request,0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();}
    @Test void privacyReceiptWaitsForAdmittedBusinessWriteThenRejectsLateWrite()throws Exception{seed();fence();var admitted=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var writer=pool.submit(()->tx.executeWithoutResult(status->{jdbc.update("UPDATE order_sagas SET attempt_count=attempt_count+1 WHERE order_id=10");admitted.countDown();try{release.await(4,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}));assertThat(admitted.await(4,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var cleanup=pool.submit(()->allowed().next(17,request,0));Thread.sleep(100);assertThat(cleanup.isDone()).isFalse();release.countDown();writer.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(cleanup.get(5,java.util.concurrent.TimeUnit.SECONDS).orElseThrow().financeCleared()).isTrue();}denied("UPDATE order_sagas SET attempt_count=attempt_count+1 WHERE order_id=10");assertThat(jdbc.queryForObject("SELECT attempt_count FROM order_sagas",Integer.class)).isEqualTo(1);}

    @Test void writerAlreadyWaitingOnReceiptCannotUseAnOlderStatementSnapshot()throws Exception{
        seed();fence();var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var cleanup=pool.submit(()->fences.underFence(17,request,db->{CommerceLocks.lock(db,10);CommerceLocks.retain(db,10,17,request);locked.countDown();try{release.await(4,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertThat(locked.await(4,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->{try{jdbc.update("UPDATE orders SET customer_email='stale@fixture.example' WHERE id=10");return false;}catch(org.springframework.dao.DataAccessException expected){return true;}});
            Thread.sleep(100);assertThat(writer.isDone()).isFalse();release.countDown();cleanup.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(writer.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT customer_email FROM orders",String.class)).isEqualTo("owner@fixture.example");
    }

    FinancePrivacyJobs jobs(){return new FinancePrivacyJobs(jdbc.getDataSource(),new DataSourceTransactionManager(jdbc.getDataSource()),fences);}
    OrderPrivacySource verified(){return new OrderPrivacySource(fences,new VerifiedCommerceFinanceGate(),true);}
    void due(){jdbc.update("UPDATE finance_privacy_jobs SET retry_at=now()-interval '1 hour'");}
    void enqueue(){jobs().consent(17,request,FinancePolicy.hash(),0);verified().next(17,request,0);}
    @Test void genuineFinanceClearanceRequiresConsentAndEveryDurablePeerReceipt(){
        seed();fence();assertThat(verified().next(17,request,0).orElseThrow().financeCleared()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_privacy_jobs",Long.class)).isZero();enqueue();
        for(int n=0;n<7;n++){due();var work=jobs().claim();assertThat(work.step()).isEqualTo(n);assertThat(verified().next(17,request,0).orElseThrow().financeCleared()).isFalse();jobs().finish(work,FinancePreparationAdapter.Result.prepared());}
        due();var finalStep=jobs().claim();jobs().seal(finalStep);assertThat(verified().next(17,request,0).orElseThrow().financeCleared()).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_privacy_receipts",Long.class)).isEqualTo(7);
        assertThat(new OrderCommerceCleanup(fences,verified()).cleanup(17,request,0).code()).isEqualTo("ORDER_REDACTED");assertThat(jdbc.queryForObject("SELECT customer_email FROM orders",String.class)).isNull();
    }
    @Test void stalePeerReplyAndAChangedOrderVersionCannotAuthorizeRedaction(){
        seed();fence();enqueue();var old=jobs().claim();jdbc.update("UPDATE finance_privacy_jobs SET run_until=now()-interval '1 second'");var newer=jobs().claim();jobs().finish(old,FinancePreparationAdapter.Result.prepared());assertThat(jdbc.queryForObject("SELECT step FROM finance_privacy_jobs",Integer.class)).isZero();jobs().finish(newer,FinancePreparationAdapter.Result.prepared());
        for(int n=1;n<7;n++){due();jobs().finish(jobs().claim(),FinancePreparationAdapter.Result.prepared());}
        jdbc.update("UPDATE orders SET version=version+1");due();jobs().seal(jobs().claim());assertThat(jdbc.queryForObject("SELECT state FROM finance_privacy_jobs",String.class)).isEqualTo("REVIEW");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT customer_email FROM orders",String.class)).isEqualTo("owner@fixture.example");
    }
    @Test void healthyWaitsDoNotConsumeFailureBudgetAndAutomaticConsentCannotResetExhaustion(){
        seed();fence();enqueue();for(int n=0;n<12;n++){due();jobs().finish(jobs().claim(),FinancePreparationAdapter.Result.waitFor("WAITING_STREAM_ACK","1-0"));}
        assertThat(jdbc.queryForObject("SELECT attempts FROM finance_privacy_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT cursor FROM finance_privacy_jobs",String.class)).isEqualTo("1-0");
        for(int n=0;n<8;n++){due();jobs().finish(jobs().claim(),null);}assertThat(jobs().claim()).isNull();jobs().consent(17,request,FinancePolicy.hash(),0);assertThat(jobs().claim()).isNull();
        jobs().consent(17,request,FinancePolicy.hash(),1);assertThat(jobs().claim()).isNotNull();assertThat(jdbc.queryForObject("SELECT customer_email FROM orders",String.class)).isEqualTo("owner@fixture.example");
    }
    @Test void interruptedLastAttemptAndAnUnreviewedConsentNeverBecomeReady(){
        seed();fence();enqueue();var work=jobs().claim();jdbc.update("UPDATE finance_privacy_jobs SET attempts=8,run_until=now()-interval '1 second'");jobs().recover();assertThat(jdbc.queryForObject("SELECT code FROM finance_privacy_jobs",String.class)).isEqualTo("RETRY_LIMIT");
        assertThatThrownBy(()->jobs().consent(17,request,"a".repeat(64),1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(verified().next(17,request,0).orElseThrow().financeCleared()).isFalse();
        jdbc.update("UPDATE finance_privacy_consents SET policy_hash=repeat('a',64)");assertThat(jobs().claim()).isNull();
    }
    @Test void financeDiscoveryIsIndependentOfCommerceCursorsAndRevisitsActiveOrders(){
        seed();fence();jobs().consent(17,request,FinancePolicy.hash(),0);
        jdbc.update("UPDATE orders SET status='PAID'");jobs().progress(17,request);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_privacy_jobs",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT scan_after FROM finance_privacy_consents",Long.class)).isEqualTo(10);
        jobs().progress(17,request);assertThat(jdbc.queryForObject("SELECT scan_after FROM finance_privacy_consents",Long.class)).isZero();
        jdbc.update("UPDATE orders SET status='CANCELLED'");jobs().progress(17,request);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_privacy_jobs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();
    }
}
