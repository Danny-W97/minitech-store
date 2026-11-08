package org.demo.chatservice.privacy;

import org.demo.platformsecurity.Access;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ChatPrivacyRequestPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("chat_privacy_fixture").withUsername("fixture").withPassword("fixture-only");
    static JdbcTemplate jdbc;static ChatPrivacyRequestStore store;static final String CAP="C".repeat(43),OTHER="D".repeat(43);
    @BeforeAll static void schema(){var source=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new ChatPrivacyRequestStore(source,new DataSourceTransactionManager(source));}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE chat_privacy_requests,chat_notification_outbox,chat_entries,chat_messages RESTART IDENTITY CASCADE");}
    long conversation(String cap){return jdbc.queryForObject("INSERT INTO chat_messages(customer_name,customer_email,subject,message,status,notification_status,created_at,updated_at,access_token_hash) VALUES('Same name','same@fixture.example','Private subject','Private text','NEW','NOT_SENT',now(),now(),?) RETURNING id",Long.class,cap==null?null:Access.hash(cap));}
    void rejects(int expected,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(expected));}
    @Test void repeatedIntentHasOneSafeRequestWithoutSavingTheCapabilityOrText(){
        long id=conversation(CAP);UUID key=UUID.randomUUID();var first=store.submit(id,CAP,key);
        assertThat(store.submit(id,CAP,key)).isEqualTo(first);assertThat(store.submit(id,CAP,UUID.randomUUID())).isEqualTo(first);assertThat(store.get(id,CAP)).isEqualTo(first);
        assertThat(first.state()).isEqualTo("MANUAL_REVIEW");assertThat(first.contentErased()).isFalse();assertThat(first.complete()).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM chat_privacy_requests")).containsOnlyKeys("conversation_id","request_id","state","created_at");
        assertThat(jdbc.queryForObject("SELECT row_to_json(r)::text FROM chat_privacy_requests r",String.class)).doesNotContain(CAP,Access.hash(CAP),"same@fixture.example","Private");
    }
    @Test void identicalEmailAndNameDoNotAuthorizeAnotherConversationOrUnprotectedHistory(){
        long first=conversation(CAP),second=conversation(OTHER),history=conversation(null);
        rejects(404,()->store.submit(second,CAP,UUID.randomUUID()));rejects(404,()->store.submit(history,CAP,UUID.randomUUID()));rejects(404,()->store.get(first,CAP));
        rejects(401,()->store.submit(first,null,UUID.randomUUID()));rejects(401,()->store.get(first,"short"));rejects(400,()->store.submit(0,CAP,UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_privacy_requests",Integer.class)).isZero();
    }
    @Test void keyCannotBeReboundToAnotherProvenConversation(){long first=conversation(CAP),second=conversation(OTHER);UUID key=UUID.randomUUID();store.submit(first,CAP,key);rejects(409,()->store.submit(second,OTHER,key));assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_privacy_requests",Integer.class)).isEqualTo(1);}
    @Test void concurrentSubmissionsAreSerializedPerConversation()throws Exception{
        long id=conversation(CAP);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return store.submit(id,CAP,UUID.randomUUID());});var b=pool.submit(()->{start.await();return store.submit(id,CAP,UUID.randomUUID());});start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_privacy_requests",Integer.class)).isEqualTo(1);
    }
    @Test void recordingIntentDoesNotChangeConversationEntriesOrNotificationWork(){
        long id=conversation(CAP);long entry=jdbc.queryForObject("INSERT INTO chat_entries(conversation_id,author,initial_message,request_key,message,created_at) VALUES(?,'CUSTOMER',true,'initial','Private entry',now()) RETURNING id",Long.class,id);
        jdbc.update("INSERT INTO chat_notification_outbox(entry_id,event_type,source_id,status,created_at) VALUES(?,'CHAT_MESSAGE',?,'PENDING',now())",entry,id);
        String query="SELECT json_build_array((SELECT json_agg(c ORDER BY id) FROM chat_messages c),(SELECT json_agg(e ORDER BY id) FROM chat_entries e),(SELECT json_agg(o ORDER BY entry_id) FROM chat_notification_outbox o))::text";
        String before=jdbc.queryForObject(query,String.class);store.submit(id,CAP,UUID.randomUUID());store.get(id,CAP);assertThat(jdbc.queryForObject(query,String.class)).isEqualTo(before);
    }
    @Test void everyStatusReadRechecksTheCurrentConversationProof(){long id=conversation(CAP);var r=store.submit(id,CAP,UUID.randomUUID());jdbc.update("UPDATE chat_messages SET access_token_hash=? WHERE id=?",Access.hash(OTHER),id);rejects(404,()->store.get(id,CAP));assertThat(store.get(id,OTHER).requestId()).isEqualTo(r.requestId());}
    @Test void physicalConversationRemovalKeepsOnlyMinimalReceiptAndEndsPublicTokenAccess(){
        long id=conversation(CAP);var request=store.submit(id,CAP,UUID.randomUUID());
        jdbc.update("DELETE FROM chat_messages WHERE id=?",id);
        rejects(404,()->store.get(id,CAP));rejects(404,()->store.submit(id,CAP,UUID.randomUUID()));
        var receipt=jdbc.queryForMap("SELECT * FROM chat_privacy_requests WHERE request_id=?",request.requestId());
        assertThat(receipt).containsOnlyKeys("conversation_id","request_id","state","created_at");
        assertThat(receipt.get("state")).isEqualTo("MANUAL_REVIEW");
        assertThat(receipt.toString()).doesNotContain(CAP,Access.hash(CAP),"same@fixture.example");
    }
}
