package org.demo.billingservice.messaging;

import org.demo.platformevents.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","messaging.consumer.enabled=false","outbox.publisher.enabled=false","notification.retry.enabled=false","messaging.invoice-stream-retention.mode=off"})
@ActiveProfiles("test") @Testcontainers
class VersionedInboxPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("identity_fixture").withUsername("fixture").withPassword("fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");}
    @Autowired PaymentEventInbox inbox;
    @Autowired EventReceiptRepository receipts;
    @Autowired RejectedEventStore rejected;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.demo.platformsecurity.privacy.PrivacyFenceStore fences;

    @BeforeEach void reset(){jdbc.execute("TRUNCATE invoices,invoice_outbox_events,event_identity_aliases,event_inbox,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");}
    Map<String,String> legacy(){var f=new LinkedHashMap<String,String>();f.put("eventType","PAYMENT_SUCCEEDED");f.put("orderId","71");f.put("amount","10.00");f.put("recipient","owner@fixture.example");f.put("eventKey","PAYMENT_SUCCEEDED:71:owner@fixture.example");f.put("eventId",EventIdentity.payment(f.get("eventKey")));f.put("deliveryAddress","Private address");return f;}
    Map<String,String> modern(Map<String,String> old){var f=new LinkedHashMap<>(old);f.put("eventId","payment-v2-"+UUID.randomUUID());f.put("eventKey","v2:"+EventIdentity.hash(old.get("eventKey")));f.put("legacyEventId",EventIdentity.payment(old.get("eventKey")));f.put("protocolVersion","2");return f;}
    void accept(Map<String,String> fields){inbox.accept(EventIdentity.id("payment-events","42-0",fields),fields);}
    @Test void oldThenNewAndReplayHaveOneBusinessEffectAndBothReaderReceipts(){
        var old=legacy();var next=modern(old);accept(old);accept(next);accept(old);accept(next);
        assertThat(receipts.findById(old.get("eventId")).orElseThrow().getPayloadHash()).isEqualTo(EventIdentity.fingerprint(old));
        assertThat(receipts.findById(next.get("eventId")).orElseThrow().getPayloadHash()).isEqualTo(EventIdentity.fingerprint(next));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();
    }
    @Test void newThenOldAndAnotherOpaqueAliasStillHaveOneBusinessEffect(){var old=legacy();var next=modern(old);accept(next);accept(old);accept(modern(old));assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();}
    @Test void changedContentNeverReusesAnAcceptedOpaqueId(){var next=modern(legacy());accept(next);var changed=new LinkedHashMap<>(next);changed.put("amount","11.00");assertThatThrownBy(()->accept(changed)).isInstanceOf(IllegalArgumentException.class);assertThat(receipts.count()).isEqualTo(2);}
    @Test void changedRecipientCannotRebindAnOpaqueIdEvenWithConsistentLegacyFields(){var next=modern(legacy());accept(next);var other=legacy();other.put("recipient","other@fixture.example");other.put("eventKey","PAYMENT_SUCCEEDED:71:other@fixture.example");var changed=modern(other);changed.put("eventId",next.get("eventId"));assertThatThrownBy(()->accept(changed)).isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isEqualTo(1);}
    @Test void receiptWrittenByAnOldV2ReaderIsVerifiedBeforeCanonicalBinding(){var next=modern(legacy());long related=71;
        jdbc.update("INSERT INTO event_inbox(event_id,payload_hash,status,related_id,received_at) VALUES(?,?,'ACCEPTED',?,now())",next.get("eventId"),EventIdentity.fingerprint(next),related);
        var changed=new LinkedHashMap<>(next);changed.put("amount","12.00");assertThatThrownBy(()->accept(changed)).isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isZero();
        accept(next);assertThat(receipts.findById(EventIdentity.envelope(next.get("eventId"),next).canonicalId())).isPresent();assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();
    }
    @Test void aliasMappingAloneAlsoRejectsRebinding(){var next=modern(legacy());jdbc.update("INSERT INTO event_identity_aliases(alias_id,canonical_id) VALUES(?,'payment-foreign')",next.get("eventId"));assertThatThrownBy(()->accept(next)).isInstanceOf(IllegalArgumentException.class);assertThat(receipts.count()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();}
    @Test void aliasAndReceiptAndBusinessInsertRollbackTogether(){
        jdbc.execute("CREATE FUNCTION fixture_abort_alias() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_id LIKE 'payment-v2-%' THEN RAISE EXCEPTION 'fixture rollback' USING ERRCODE='23514'; END IF;RETURN NEW;END $$");
        jdbc.execute("CREATE TRIGGER fixture_abort_alias AFTER INSERT ON event_inbox FOR EACH ROW EXECUTE FUNCTION fixture_abort_alias()");var next=modern(legacy());
        try{assertThatThrownBy(()->accept(next)).isInstanceOf(RuntimeException.class);assertThat(receipts.count()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();}
        finally{jdbc.execute("DROP TRIGGER fixture_abort_alias ON event_inbox");jdbc.execute("DROP FUNCTION fixture_abort_alias()");}
        accept(next);assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();
    }
    @Test void postPrivacyNewAndMalformedMessagesKeepOnlyMinimalSuppressionReceipts(){
        jdbc.update("INSERT INTO commerce_privacy_locks(order_id,customer_id,request_id) VALUES(71,17,?)",UUID.randomUUID());var next=modern(legacy());accept(next);assertThat(receipts.findById(next.get("legacyEventId")).orElseThrow().getStatus()).isEqualTo("SUPPRESSED");assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();
        rejected.reject("payment-events","99-0",Map.of("orderId","71","eventType","broken","recipient","owner@fixture.example","private","secret text"),"Private error");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE payload IS NOT NULL OR error IS NOT NULL",Long.class)).isZero();
        rejected.reject("payment-events","100-0",Map.of("orderId","72","private","other account"),"Manual assessment");assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE payload IS NOT NULL",Long.class)).isEqualTo(1);
    }
    @Test void streamRecordFallbackAndMissingExplicitIdRemainIdempotent(){var fields=legacy();fields.remove("eventId");accept(fields);accept(fields);fields.remove("eventKey");accept(fields);accept(fields);assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices",Long.class)).isZero();}
    @Test void preparationChecksDocumentOwnershipAmountsCurrencyAndStateBeforeErasingCopies(){
        var request=UUID.randomUUID();fences.fence(17,request);
        jdbc.update("INSERT INTO invoices(order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,currency,status,created_at) VALUES(71,18,'owner@fixture.example','FIX/71','PERSON','Jan Fixture','Private street','00-001','Test','PL',8.13,1.87,12,'PLN','ISSUED',now())");
        org.demo.platformsecurity.privacy.CommerceOrderSource source=(customer,id,cursor)->Optional.of(new org.demo.platformsecurity.privacy.CommerceOrderSource.Entry(71,"ELIGIBLE",false,"COMPLETED",0,false,false,0,new java.math.BigDecimal("10.00"),"PAID",true));
        try(var mocked=org.mockito.Mockito.mockConstruction(org.demo.platformsecurity.privacy.FinanceHttp.class,(mock,context)->org.mockito.Mockito.when(mock.paymentEvents(17,request,71,0)).thenReturn(List.of()))){
            var adapter=new org.demo.billingservice.privacy.BillingFinancePreparation(fences,source,org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class),org.mockito.Mockito.mock(org.demo.platformsecurity.ServiceAuthenticationInterceptor.class),"http://127.0.0.1:1","fixture","fixture-ledger");
            assertThat(adapter.prepare(17,request,71,0,"0-0").code()).isEqualTo("FINANCE_REVIEW_REQUIRED");
            jdbc.update("UPDATE invoices SET customer_id=17");assertThat(adapter.prepare(17,request,71,0,"0-0").code()).isEqualTo("FINANCE_REVIEW_REQUIRED");
            jdbc.update("UPDATE invoices SET gross_amount=10,currency='EUR'");assertThat(adapter.prepare(17,request,71,0,"0-0").code()).isEqualTo("FINANCE_REVIEW_REQUIRED");
            jdbc.update("UPDATE invoices SET currency='PLN',status='CANCELLED'");assertThat(adapter.prepare(17,request,71,0,"0-0").code()).isEqualTo("FINANCE_REVIEW_REQUIRED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();
            jdbc.update("UPDATE invoices SET status='ISSUED'");
            jdbc.update("INSERT INTO invoice_outbox_events(event_id,order_id,payload,status,attempts,next_attempt_at,created_at,published_at) VALUES('invoice-issued-1',71,'{}','PUBLISHED',0,now(),now(),now())");
            assertThat(adapter.prepare(17,request,71,0,"0-0").state()).isEqualTo("MORE");
            assertThat(jdbc.queryForObject("SELECT payload FROM invoice_outbox_events",String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT customer_email FROM invoices",String.class)).isEqualTo("owner@fixture.example");
            assertThat(jdbc.queryForObject("SELECT gross_amount FROM invoices",java.math.BigDecimal.class)).isEqualByComparingTo("10.00");
        }
    }
    @Test void malformedLateEventAtTheBigintBoundaryNeverRestoresRawDiagnostics(){
        jdbc.update("INSERT INTO commerce_privacy_locks(order_id,customer_id,request_id) VALUES(?,17,?)",Long.MAX_VALUE,UUID.randomUUID());
        rejected.reject("payment-events","901-0",Map.of("orderId",Long.toString(Long.MAX_VALUE),"eventType","broken","private","Private text"),"Private failure");
        assertThat(jdbc.queryForObject("SELECT payload IS NULL AND error IS NULL AND status='SUPPRESSED' AND order_id=? FROM event_inbox",Boolean.class,Long.MAX_VALUE)).isTrue();
        assertThatThrownBy(()->jdbc.update("INSERT INTO event_inbox(event_id,payload_hash,status,payload,received_at) VALUES('legacy-bigint',repeat('b',64),'REJECTED','{\"orderId\":\"9223372036854775807\",\"private\":\"Private text\"}',now())")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        rejected.reject("payment-events","902-0",Map.of("orderId","9223372036854775808","private","Unqualified fixture"),"Manual review");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE status='REJECTED' AND order_id IS NULL",Long.class)).isEqualTo(1);
    }
}
