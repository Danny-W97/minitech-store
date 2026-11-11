package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionAssessment;
import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import org.demo.orderservice.privacy.CommerceRetentionRedactionWorker.Outcome;
import org.demo.orderservice.privacy.CommerceRetentionRedactionWorker.RedactionResult;
import org.demo.orderservice.privacy.CommerceRetentionMinimumRemovalWorker.RemovalResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CommerceRetentionRedactionPostgresIT {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("retention_redaction")
            .withUsername("fixture")
            .withPassword("fixture-only");

    static DriverManagerDataSource ds;
    static DataSourceTransactionManager manager;
    static JdbcTemplate jdbc;

    CommerceRetentionJobs jobs;
    CommerceRetentionHolds holds;
    CommerceRetentionRedactionWorker redactionWorker;
    CommerceRetentionMinimumRemovalWorker minimumWorker;

    @BeforeAll
    static void setup() {
        ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        manager = new DataSourceTransactionManager(ds);
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
            TRUNCATE commerce_retention_holds, commerce_retention_hold_scopes,
                     commerce_retention_payment_evidence, commerce_retention_bindings,
                     commerce_retention_evidence, commerce_retention_jobs,
                     commerce_retention_order_state, account_privacy_fences,
                     commerce_privacy_locks, order_items, order_sagas, orders RESTART IDENTITY CASCADE
            """);

        jobs = new CommerceRetentionJobs(ds, manager);
        holds = new CommerceRetentionHolds(ds, manager);
        redactionWorker = new CommerceRetentionRedactionWorker(ds, manager);
        minimumWorker = new CommerceRetentionMinimumRemovalWorker(ds, manager);
    }

    long seedOrder(long id, long customerId) {
        return jdbc.queryForObject("""
            INSERT INTO orders(id, customer_id, customer_email, total_price, unit_price, quantity, status,
                delivery_first_name, delivery_last_name, delivery_street, delivery_postal_code, delivery_city,
                delivery_country, delivery_phone, delivery_method, delivery_cost, discount_amount,
                delivery_date, delivery_slot, pickup_location,
                fulfillment_type, idempotency_key, checkout_request_hash, checkout_principal,
                inventory_reservation_id, delivery_tracking_number, delivery_status, delivery_revision,
                created_at, updated_at)
            VALUES (?, ?, 'alice@example.com', 123.45, 61.725, 2, 'COMPLETED',
                'Alice', 'Smith', 'Main St 1', '00-001', 'Warsaw', 'PL', '+48123456789',
                'STANDARD_COURIER', 12.34, 0.00,
                '2026-10-31', '08:00-12:00', 'PARCEL_LOCKER_1',
                'HOME_DELIVERY', 'idemp-key-1', repeat('a', 64), 'customer:17',
                42, 'TRACK-123', 'DELIVERED', 0,
                now() - interval '500 days', now() - interval '500 days')
            RETURNING id
            """, Long.class, id, customerId);
    }

    void seedItem(long orderId) {
        jdbc.update("""
            INSERT INTO order_items(order_id, product_id, sku, product_name, quantity, unit_price, total_price)
            VALUES(?, 101, 'SKU-1', 'Super Widget', 2, 61.725, 123.45)
            """, orderId);
    }

    void seedSaga(long orderId) {
        jdbc.update("INSERT INTO order_sagas(order_id, state, created_at, updated_at) VALUES(?, 'COMPLETED', now() - interval '500 days', now() - interval '500 days')", orderId);
    }

    void populateEvidence(Subject s, Phase phase, Instant reconciledAt, Instant redactedAt, boolean confirmGuards) {
        for (var p : Participant.values()) {
            jdbc.update("""
                INSERT INTO commerce_retention_evidence(order_id, generation, phase, kind, source, policy, state, reconciled_at, redacted_at)
                VALUES(?, ?, ?, 'CHAIN', ?, ?, 'CONFIRMED', ?, ?)
                ON CONFLICT (order_id, generation, phase, kind, source)
                DO UPDATE SET state='CONFIRMED', reconciled_at=EXCLUDED.reconciled_at, redacted_at=EXCLUDED.redacted_at
                """, s.orderId(), s.chainGeneration(), phase.name(), p.name(), CommerceRetentionAssessment.POLICY,
                Timestamp.from(reconciledAt), redactedAt == null ? null : Timestamp.from(redactedAt));
        }

        if (confirmGuards) {
            for (var g : CommerceRetentionAssessment.requiredGuards(phase)) {
                jdbc.update("""
                    INSERT INTO commerce_retention_evidence(order_id, generation, phase, kind, source, policy, state)
                    VALUES(?, ?, ?, 'GUARD', ?, ?, 'CONFIRMED')
                    ON CONFLICT (order_id, generation, phase, kind, source)
                    DO UPDATE SET state='CONFIRMED'
                    """, s.orderId(), s.chainGeneration(), phase.name(), g.name(), CommerceRetentionAssessment.POLICY);
            }
        }
    }

    void seedPurchase() { seedOrder(10,17); seedItem(10); seedSaga(10); }

    void redactRootFixture() {
        jdbc.update("UPDATE orders SET customer_email=NULL,delivery_first_name=NULL,delivery_last_name=NULL,delivery_street=NULL,delivery_postal_code=NULL,delivery_city=NULL,delivery_country=NULL,delivery_phone=NULL,delivery_date=NULL,delivery_slot=NULL,pickup_location=NULL,privacy_redacted=TRUE WHERE id=10");
    }

    void priorRedactionFixture() {
        redactRootFixture();
        jdbc.update("UPDATE order_items SET product_name=NULL WHERE order_id=10");
    }

    @Test void noExecutionAuthorityNeverMutatesDataEvenWithAllConfirmedMetadata() {
        seedPurchase(); var subject=jobs.prepare(10,Phase.REDACTION); var now=Instant.now();
        populateEvidence(subject,Phase.REDACTION,now.minus(Duration.ofDays(400)),null,true);
        jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',code='AGE_REACHED' WHERE order_id=10");
        var result=redactionWorker.redact(subject,now);
        assertThat(result.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(result.code()).isEqualTo("RETENTION_EXECUTION_NOT_CONFIGURED");
        assertThat(result.activationAllowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT customer_email FROM orders WHERE id=10",String.class)).isEqualTo("alice@example.com");
        assertThat(jdbc.queryForObject("SELECT redacted_at IS NULL FROM commerce_retention_evidence WHERE source='ORDER'",Boolean.class)).isTrue();
    }

    @Test void callerClockAndFixtureBooleanCannotEnableDestructiveExecution() {
        seedPurchase(); var subject=jobs.prepare(10,Phase.MINIMUM_REMOVAL);
        for(boolean flag:new boolean[]{false,true}) {
            var result=minimumWorker.assessAndRemove(subject,Instant.now().plus(Duration.ofDays(10000)),flag);
            assertThat(result.outcome()).isEqualTo(CommerceRetentionMinimumRemovalWorker.Outcome.BLOCKED);
            assertThat(result.removedAt()).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items",Integer.class)).isOne();
    }

    @Test void irreversibleFlagDoesNotRequireAnAccountDeletionLock() {
        seedPurchase(); priorRedactionFixture();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Integer.class)).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE orders SET privacy_redacted=FALSE,customer_email='old@fixture.test',delivery_first_name='Old',delivery_last_name='Old',delivery_street='Old',delivery_postal_code='00-001',delivery_city='Old',delivery_country='PL' WHERE id=10"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT privacy_redacted FROM orders WHERE id=10",Boolean.class)).isTrue();
    }

    @Test void redactedItemCannotBeRestoredByLateUpdate() {
        seedPurchase(); priorRedactionFixture();
        assertThatThrownBy(()->jdbc.update("UPDATE order_items SET product_name='Old fixture' WHERE order_id=10"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void redactedItemCannotBeReinsertedByOldCommand() {
        seedPurchase(); priorRedactionFixture();
        assertThatThrownBy(()->jdbc.update("INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) VALUES(10,102,'OLD','Old',1,1,1)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items",Integer.class)).isOne();
    }

    @Test void redactedSagaCannotRegainFreeText() {
        seedPurchase(); priorRedactionFixture();
        assertThatThrownBy(()->jdbc.update("UPDATE order_sagas SET last_error='Old private fixture' WHERE order_id=10"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void contactAndScheduleConstraintsRemainEnforced() {
        seedPurchase(); priorRedactionFixture();
        assertThatThrownBy(()->jdbc.update("UPDATE orders SET customer_email='old@fixture.test' WHERE id=10"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE orders SET delivery_date='2026-11-01' WHERE id=10"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void concurrentOldChildWriteCannotCrossRedactionCommit() throws Exception {
        seedPurchase(); jdbc.update("UPDATE order_items SET product_name=NULL WHERE order_id=10");
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var started=new java.util.concurrent.CountDownLatch(1);
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
                redactRootFixture();
                pending.set(executor.submit(()->{started.countDown();return jdbc.update("UPDATE order_items SET product_name='Old fixture' WHERE order_id=10");}));
                try {
                    assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(()->pending.get().get(250,java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } catch(InterruptedException interrupted) {throw new IllegalStateException(interrupted);}
            });
            assertThatThrownBy(()->pending.get().get(5,java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT product_name IS NULL FROM order_items WHERE order_id=10",Boolean.class)).isTrue();
    }

    @Test void ordinaryUnredactedPurchasesStillAllowBusinessUpdates() {
        seedPurchase();
        assertThat(jdbc.update("UPDATE orders SET customer_email='new@fixture.test' WHERE id=10")).isOne();
        assertThat(jdbc.update("UPDATE order_items SET product_name='Changed fixture' WHERE order_id=10")).isOne();
    }
}
