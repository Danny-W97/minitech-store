package org.demo.paymentservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class PaymentDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static DriverManagerDataSource source;private static JdbcTemplate jdbc;
    private final OrderAccess orders=mock(OrderAccess.class);
    @BeforeAll static void database(){
        source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE payment_refunds,payments RESTART IDENTITY CASCADE");}
    @Test void paymentAndOptionalRefundAreReadFromTheStoredOrderNotPaymentId(){
        long id=payment(99);refund(id,99);payment(100);var result=new PaymentDataExportService(source,orders).export(99);
        assertThat(result.payment().paymentId()).isEqualTo(id);assertThat(result.payment().orderId()).isEqualTo(99L);
        assertThat(result.payment().amount()).isEqualByComparingTo("123.45");assertThat(result.payment().currency()).isEqualTo("PLN");
        assertThat(result.refund().amount()).isEqualByComparingTo("123.45");assertThat(result.refund().paymentId()).isEqualTo(id);
        assertThat(result.refund().orderId()).isEqualTo(99L);assertThat(result.complete()).isFalse();verify(orders).requireCustomerOwner(99L);
    }
    @Test void noRefundRecordAndUncollectedPaymentPreserveNulls(){
        payment(99);var result=new PaymentDataExportService(source,orders).export(99);
        assertThat(result.refund()).isNull();assertThat(result.payment().collectedAt()).isNull();
        assertThat(result.omissions()).contains(new PaymentDataExport.Omission("refundAvailability","stored-record-only"));
    }
    @Test void storedCollectionAndRefundSubmissionTimesArePreserved(){
        long id=payment(99);refund(id,99);jdbc.update("UPDATE payments SET collected_at='2025-01-02T03:04:05Z',payment_method='CASH_AT_PICKUP' WHERE id=?",id);
        jdbc.update("UPDATE payment_refunds SET submitted_at='2025-01-03T04:05:06Z' WHERE payment_id=?",id);
        var result=new PaymentDataExportService(source,orders).export(99);
        assertThat(result.payment().collectedAt()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(result.refund().submittedAt()).isEqualTo(Instant.parse("2025-01-03T04:05:06Z"));
    }
    @Test void allStoredPaymentStatusesAreExportedWithoutProviderReconciliation(){
        long id=payment(99);var service=new PaymentDataExportService(source,orders);
        for(var status:List.of("PENDING","SUCCEEDED","FAILED","REFUNDED")) {
            jdbc.update("UPDATE payments SET status=? WHERE id=?",status,id);assertThat(service.export(99).payment().status().name()).isEqualTo(status);
        }
        assertThat(service.export(99).omissions()).contains(new PaymentDataExport.Omission("providerAndDomainReconciliation","not-performed"));
    }
    @Test void allRefundStatusesAndNullSubmissionTimeArePreserved(){
        long id=payment(99);refund(id,99);var service=new PaymentDataExportService(source,orders);
        for(var status:List.of("REQUESTED","PENDING","SUCCEEDED","FAILED","REVIEW_REQUIRED")) {
            jdbc.update("UPDATE payment_refunds SET status=? WHERE payment_id=?",status,id);assertThat(service.export(99).refund().status().name()).isEqualTo(status);
        }
        assertThat(service.export(99).refund().submittedAt()).isNull();
    }
    @Test void missingPaymentIs404AfterOwnershipCheck(){
        assertThatThrownBy(()->new PaymentDataExportService(source,orders).export(99)).isInstanceOfSatisfying(ResponseStatusException.class,
                error->assertThat(error.getStatusCode().value()).isEqualTo(404));verify(orders).requireCustomerOwner(99L);
    }
    @Test void refundPointingAtAnotherOrderFailsClosedInsteadOfBeingIncludedOrHidden(){
        long id=payment(99);refund(id,100);
        assertThatThrownBy(()->new PaymentDataExportService(source,orders).export(99)).isInstanceOfSatisfying(ResponseStatusException.class,
                error->assertThat(error.getStatusCode().value()).isEqualTo(503));
    }
    @Test void restrictedRoleCannotReadSecretsDiagnosticsOrQueuesButCanExport(){
        long id=payment(99);refund(id,99);jdbc.execute("CREATE ROLE payment_export LOGIN PASSWORD 'fixture-projection' NOSUPERUSER");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO payment_export");
        jdbc.execute("GRANT SELECT (id,order_id,amount,currency,payment_method,status,collected_at,created_at,updated_at) ON payments TO payment_export");
        jdbc.execute("GRANT SELECT (id,payment_id,order_id,amount,currency,status,submitted_at,created_at,updated_at) ON payment_refunds TO payment_export");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"payment_export","fixture-projection");var limited=new JdbcTemplate(restricted);
        for(var sql:List.of("SELECT checkout_url FROM payments","SELECT idempotency_key FROM payments","SELECT provider_reference FROM payments",
                "SELECT failure_reason FROM payments","SELECT requested_by FROM payment_refunds","SELECT operation_id FROM payment_refunds",
                "SELECT last_error FROM payment_refunds","SELECT * FROM payment_follow_ups","SELECT * FROM stripe_webhook_receipts"))
            assertThatThrownBy(()->limited.queryForList(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(new PaymentDataExportService(restricted,orders).export(99).refund().paymentId()).isEqualTo(id);
    }
    @Test void exportDoesNotChangeMoneyStatusOrAnyStoredWorkflowFields(){
        long id=payment(99);refund(id,99);String before=snapshot();var service=new PaymentDataExportService(source,orders);service.export(99);service.export(99);
        assertThat(snapshot()).isEqualTo(before);verify(orders,times(2)).requireCustomerOwner(99L);
    }
    @Test void existingUniqueIndexesBoundTheJoinToOnePaymentAndOneRefund(){
        long id=payment(99);refund(id,99);assertThatThrownBy(()->payment(99)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(()->refund(id,99)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='payments' AND indexdef LIKE 'CREATE UNIQUE INDEX% (order_id)'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='payment_refunds' AND indexdef LIKE 'CREATE UNIQUE INDEX% (payment_id)'",Integer.class)).isEqualTo(1);
    }
    private long payment(long order){return jdbc.queryForObject("""
            INSERT INTO payments(order_id,idempotency_key,amount,currency,payment_method,status,provider_reference,failure_reason,
              checkout_session_id,checkout_url,stripe_intent_id,stripe_error,processing_token,processing_until,created_at,updated_at)
            VALUES (?,gen_random_uuid()::text,123.45,'PLN','CARD','SUCCEEDED','fixture-private-provider','fixture-private-failure',
              gen_random_uuid()::text,'https://fixture.invalid/private-checkout',gen_random_uuid()::text,'fixture-private-stripe',
              gen_random_uuid()::text,now()+interval '1 hour',now(),now()) RETURNING id
            """,Long.class,order);}
    private void refund(long payment,long order){jdbc.update("""
            INSERT INTO payment_refunds(payment_id,order_id,request_key,operation_id,amount,currency,intent_id,provider_refund_id,
              status,automatic,reason,requested_by,last_error,processing_token,processing_until,created_at,updated_at)
            VALUES (?,?,gen_random_uuid()::text,gen_random_uuid()::text,123.45,'PLN',gen_random_uuid()::text,gen_random_uuid()::text,
              'PENDING',false,'OPERATOR_RECONCILIATION','private-operator@fixture.example','fixture-private-error',
              gen_random_uuid()::text,now()+interval '1 hour',now(),now())
            """,payment,order);}
    private String snapshot(){return jdbc.queryForObject("SELECT json_build_array((SELECT json_agg(p ORDER BY id) FROM payments p),(SELECT json_agg(r ORDER BY id) FROM payment_refunds r))::text",String.class);}
}
