package org.demo.notificationservice.messaging;

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
import org.demo.notificationservice.notification.*;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","messaging.consumer.enabled=false","outbox.publisher.enabled=false","notification.retry.enabled=false","messaging.invoice-stream-retention.mode=off"})
@ActiveProfiles("test") @Testcontainers
class VersionedInboxPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("identity_fixture").withUsername("fixture").withPassword("fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");}
    @Autowired NotificationEventInbox inbox;
    @Autowired EventReceiptRepository receipts;
    @Autowired RejectedEventStore rejected;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRepository notifications;
    @Autowired org.demo.notificationservice.privacy.NotificationFinancePreparation preparation;
    @Autowired org.demo.platformsecurity.privacy.PrivacyFenceStore fences;
    @Autowired NotificationService service;
    @MockitoBean NotificationDeliveryService delivery;

    @BeforeEach void reset(){jdbc.execute("TRUNCATE account_privacy_copy_locks,notification_dead_letters,notifications,event_identity_aliases,event_inbox,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");when(delivery.deliver(any(),eq(false))).thenReturn(NotificationDeliveryResult.sent("fixture-provider"));}
    Map<String,String> legacy(){var f=new LinkedHashMap<String,String>();f.put("eventType","PAYMENT_SUCCEEDED");f.put("orderId","71");f.put("amount","10.00");f.put("recipient","owner@fixture.example");f.put("eventKey","PAYMENT_SUCCEEDED:71:owner@fixture.example");f.put("eventId",EventIdentity.payment(f.get("eventKey")));f.put("deliveryAddress","Private address");return f;}
    Map<String,String> modern(Map<String,String> old){var f=new LinkedHashMap<>(old);f.put("eventId","payment-v2-"+UUID.randomUUID());f.put("eventKey","v2:"+EventIdentity.hash(old.get("eventKey")));f.put("legacyEventId",EventIdentity.payment(old.get("eventKey")));f.put("protocolVersion","2");return f;}
    void accept(Map<String,String> fields){inbox.accept(EventIdentity.id("payment-events","42-0",fields),fields,new CreateNotificationRequest(NotificationEventType.valueOf(fields.get("eventType")),Long.valueOf(fields.get("orderId")),fields.get("recipient"),NotificationChannel.EMAIL,"Fixture","Private content",false));}
    @Test void oldThenNewAndReplayHaveOneBusinessEffectAndBothReaderReceipts(){
        var old=legacy();var next=modern(old);accept(old);accept(next);accept(old);accept(next);
        assertThat(receipts.findById(old.get("eventId")).orElseThrow().getPayloadHash()).isEqualTo(EventIdentity.fingerprint(old));
        assertThat(receipts.findById(next.get("eventId")).orElseThrow().getPayloadHash()).isEqualTo(EventIdentity.fingerprint(next));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);long id=notifications.findAll().getFirst().getId();service.retry(id);service.retry(id);verify(delivery,times(1)).deliver(any(),eq(false));
    }
    @Test void newThenOldAndAnotherOpaqueAliasStillHaveOneBusinessEffect(){var old=legacy();var next=modern(old);accept(next);accept(old);accept(modern(old));assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isEqualTo(2);assertThat(notifications.count()).isEqualTo(1);long id=notifications.findAll().getFirst().getId();service.retry(id);service.retry(id);verify(delivery,times(1)).deliver(any(),eq(false));}
    @Test void changedContentNeverReusesAnAcceptedOpaqueId(){var next=modern(legacy());accept(next);var changed=new LinkedHashMap<>(next);changed.put("amount","11.00");assertThatThrownBy(()->accept(changed)).isInstanceOf(IllegalArgumentException.class);assertThat(receipts.count()).isEqualTo(2);}
    @Test void changedRecipientCannotRebindAnOpaqueIdEvenWithConsistentLegacyFields(){var next=modern(legacy());accept(next);var other=legacy();other.put("recipient","other@fixture.example");other.put("eventKey","PAYMENT_SUCCEEDED:71:other@fixture.example");var changed=modern(other);changed.put("eventId",next.get("eventId"));assertThatThrownBy(()->accept(changed)).isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isEqualTo(1);}
    @Test void receiptWrittenByAnOldV2ReaderIsVerifiedBeforeCanonicalBinding(){var next=modern(legacy());var prior=service.create(new CreateNotificationRequest(NotificationEventType.PAYMENT_SUCCEEDED,71L,"owner@fixture.example",NotificationChannel.EMAIL,"Fixture","Private content",false));long related=prior.id();
        jdbc.update("INSERT INTO event_inbox(event_id,payload_hash,status,related_id,received_at) VALUES(?,?,'ACCEPTED',?,now())",next.get("eventId"),EventIdentity.fingerprint(next),related);
        var changed=new LinkedHashMap<>(next);changed.put("amount","12.00");assertThatThrownBy(()->accept(changed)).isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isZero();
        accept(next);assertThat(receipts.findById(EventIdentity.envelope(next.get("eventId"),next).canonicalId())).isPresent();assertThat(notifications.count()).isEqualTo(1);long id=notifications.findAll().getFirst().getId();service.retry(id);service.retry(id);verify(delivery,times(1)).deliver(any(),eq(false));
    }
    @Test void aliasMappingAloneAlsoRejectsRebinding(){var next=modern(legacy());jdbc.update("INSERT INTO event_identity_aliases(alias_id,canonical_id) VALUES(?,'payment-foreign')",next.get("eventId"));assertThatThrownBy(()->accept(next)).isInstanceOf(IllegalArgumentException.class);assertThat(receipts.count()).isZero();assertThat(notifications.count()).isZero();}
    @Test void aliasAndReceiptAndBusinessInsertRollbackTogether(){
        jdbc.execute("CREATE FUNCTION fixture_abort_alias() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_id LIKE 'payment-v2-%' THEN RAISE EXCEPTION 'fixture rollback' USING ERRCODE='23514'; END IF;RETURN NEW;END $$");
        jdbc.execute("CREATE TRIGGER fixture_abort_alias AFTER INSERT ON event_inbox FOR EACH ROW EXECUTE FUNCTION fixture_abort_alias()");var next=modern(legacy());
        try{assertThatThrownBy(()->accept(next)).isInstanceOf(RuntimeException.class);assertThat(receipts.count()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM event_identity_aliases",Long.class)).isZero();assertThat(notifications.count()).isZero();}
        finally{jdbc.execute("DROP TRIGGER fixture_abort_alias ON event_inbox");jdbc.execute("DROP FUNCTION fixture_abort_alias()");}
        accept(next);assertThat(notifications.count()).isEqualTo(1);long id=notifications.findAll().getFirst().getId();service.retry(id);service.retry(id);verify(delivery,times(1)).deliver(any(),eq(false));
    }
    @Test void postPrivacyNewAndMalformedMessagesKeepOnlyMinimalSuppressionReceipts(){
        jdbc.update("INSERT INTO commerce_privacy_locks(order_id,customer_id,request_id) VALUES(71,17,?)",UUID.randomUUID());var next=modern(legacy());accept(next);assertThat(receipts.findById(next.get("legacyEventId")).orElseThrow().getStatus()).isEqualTo("SUPPRESSED");assertThat(notifications.count()).isZero();
        rejected.reject("payment-events","99-0",Map.of("orderId","71","eventType","broken","recipient","owner@fixture.example","private","secret text"),"Private error");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE payload IS NOT NULL OR error IS NOT NULL",Long.class)).isZero();
        rejected.reject("payment-events","100-0",Map.of("orderId","72","private","other account"),"Manual assessment");assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE payload IS NOT NULL",Long.class)).isEqualTo(1);
    }
    @Test void streamRecordFallbackAndMissingExplicitIdRemainIdempotent(){var fields=legacy();fields.remove("eventId");accept(fields);accept(fields);fields.remove("eventKey");accept(fields);accept(fields);assertThat(notifications.count()).isEqualTo(1);long id=notifications.findAll().getFirst().getId();service.retry(id);service.retry(id);verify(delivery,times(1)).deliver(any(),eq(false));}

    void security(String event,long source,Long customer,String recipient){jdbc.update("INSERT INTO notifications(event_type,source_id,subject_customer_id,recipient,channel,subject,message,status,created_at,updated_at,sent_at,attempt_count) VALUES(?,?,?,?,'EMAIL','Private subject','Private reset link','SENT',now(),now(),now(),1)",event,source,customer,recipient);}
    @Test void accountCleanupNeverTreatsAResetTokenIdAsACustomerId(){
        jdbc.execute("ALTER TABLE notifications DISABLE TRIGGER finance_privacy_write");
        try{security("PASSWORD_RESET_REQUESTED",17,null,"owner@fixture.example");}finally{jdbc.execute("ALTER TABLE notifications ENABLE TRIGGER finance_privacy_write");}
        security("CUSTOMER_REGISTERED",17,null,"owner@fixture.example");security("PASSWORD_RESET_REQUESTED",900,17L,"owner@fixture.example");security("PASSWORD_RESET_REQUESTED",17,18L,"other@fixture.example");security("CHAT_REPLY_SENT",17,null,"owner@fixture.example");
        UUID request=UUID.randomUUID();fences.fence(17,request);assertThat(preparation.account(17,request).state()).isEqualTo("MORE");assertThat(preparation.account(17,request).state()).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE privacy_redacted",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT recipient FROM notifications WHERE event_type='PASSWORD_RESET_REQUESTED' AND source_id=17 AND subject_customer_id IS NULL",String.class)).isEqualTo("owner@fixture.example");
        assertThat(jdbc.queryForObject("SELECT recipient FROM notifications WHERE subject_customer_id=18",String.class)).isEqualTo("other@fixture.example");
        assertThat(jdbc.queryForObject("SELECT recipient FROM notifications WHERE event_type='CHAT_REPLY_SENT'",String.class)).isEqualTo("owner@fixture.example");
        assertThatThrownBy(()->security("PASSWORD_RESET_REQUESTED",901,17L,"late@fixture.example")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        security("PASSWORD_RESET_REQUESTED",17,18L,"new-other@fixture.example");
    }
    @Test void activeSecurityMessagesWaitWithoutCancellationAndRetiredMessagesCannotResend(){
        security("CUSTOMER_REGISTERED",17,17L,"owner@fixture.example");jdbc.update("UPDATE notifications SET status='PENDING',sent_at=NULL");UUID request=UUID.randomUUID();fences.fence(17,request);
        assertThat(preparation.account(17,request).code()).isEqualTo("WAITING_NOTIFICATION_PROCESS");assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_copy_locks",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT status FROM notifications",String.class)).isEqualTo("PENDING");
        jdbc.update("UPDATE notifications SET status='SENT',sent_at=now()");preparation.account(17,request);preparation.account(17,request);
        var replay=service.create(new CreateNotificationRequest(NotificationEventType.CUSTOMER_REGISTERED,17L,"owner@fixture.example",NotificationChannel.EMAIL,"Old subject","Old message",false));
        assertThat(replay.recipient()).isNull();service.retry(replay.id());verifyNoInteractions(delivery);
    }
    @Test void legacyResetRetryCannotQualifyAnUnprovenAccountOrOverwriteItsContent(){
        jdbc.execute("ALTER TABLE notifications DISABLE TRIGGER finance_privacy_write");
        try{security("PASSWORD_RESET_REQUESTED",17,null,"owner@fixture.example");}finally{jdbc.execute("ALTER TABLE notifications ENABLE TRIGGER finance_privacy_write");}
        jdbc.update("UPDATE notifications SET status='FAILED',sent_at=NULL,next_attempt_at=now()");
        assertThatThrownBy(()->service.create(new CreateNotificationRequest(NotificationEventType.PASSWORD_RESET_REQUESTED,17L,"owner@fixture.example",NotificationChannel.EMAIL,"Replacement subject","Replacement message",false,null,17L))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT subject_customer_id FROM notifications",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT message FROM notifications",String.class)).isEqualTo("Private reset link");verifyNoInteractions(delivery);
    }
    @Test void openDeadLetterWaitsAndOnlyItsReconciledCopyCanBeRedacted(){
        security("CUSTOMER_REGISTERED",17,17L,"owner@fixture.example");
        jdbc.update("INSERT INTO notification_dead_letters(notification_id,event_type,source_id,recipient,channel,subject,message,status,failure_reason,attempt_count,created_at,updated_at) SELECT id,event_type,source_id,recipient,channel,subject,message,'OPEN','Private error',1,now(),now() FROM notifications");
        UUID request=UUID.randomUUID();fences.fence(17,request);
        assertThat(preparation.account(17,request).code()).isEqualTo("WAITING_DEAD_LETTER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_copy_locks",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT message FROM notification_dead_letters",String.class)).isEqualTo("Private reset link");
        jdbc.update("UPDATE notification_dead_letters SET status='RESOLVED',resolved_at=now()");
        assertThat(preparation.account(17,request).state()).isEqualTo("MORE");assertThat(preparation.account(17,request).state()).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT recipient IS NULL AND message IS NULL AND failure_reason IS NULL AND status='RESOLVED' AND resolved_at IS NOT NULL FROM notification_dead_letters",Boolean.class)).isTrue();
        assertThatThrownBy(()->jdbc.update("UPDATE notification_dead_letters SET message='Restored private text'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        service.replayDeadLetter(jdbc.queryForObject("SELECT id FROM notification_dead_letters",Long.class));verifyNoInteractions(delivery);
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
