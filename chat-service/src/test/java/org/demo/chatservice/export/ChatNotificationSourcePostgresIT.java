package org.demo.chatservice.export;

import org.demo.platformsecurity.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ChatNotificationSourcePostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("proof_fixture").withUsername("fixture").withPassword("fixture-only");
    private static JdbcTemplate jdbc;private static ChatNotificationSourceController controller;
    @BeforeAll static void database(){var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);controller=new ChatNotificationSourceController(source);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE chat_notification_outbox,chat_entries,chat_messages RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO chat_messages(customer_name,customer_email,subject,message,status,notification_status,created_at,updated_at,access_token_hash)
                VALUES ('Test','same@fixture.example','Temat','Treść','NEW','PENDING',now(),now(),?),
                       ('Test','same@fixture.example','Temat','Treść','NEW','PENDING',now(),now(),?)
                """,Access.hash("C".repeat(43)),Access.hash("D".repeat(43)));
        jdbc.update("""
                INSERT INTO chat_entries(conversation_id,author,initial_message,request_key,message,created_at)
                VALUES (1,'SUPPORT',false,'support','Odpowiedź',now()),(1,'CUSTOMER',true,'customer','Pytanie',now()),
                       (2,'SUPPORT',false,'other','Inna',now())
                """);actor(new Actor("SERVICE","notification-service",null,null),"C".repeat(43));}
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test void legacyIdsAndNewEntryIdsUseDifferentGraphs(){
        assertThat(controller.access("CHAT_REPLY_SENT",1).getBody().conversationId()).isEqualTo(1);
        assertThat(controller.access("CHAT_ENTRY_REPLIED",1).getBody().conversationId()).isEqualTo(1);
        status(404,()->controller.access("CHAT_ENTRY_RECEIVED",2));status(404,()->controller.access("CHAT_MESSAGE_RECEIVED",1));
        status(404,()->controller.access("CHAT_ENTRY_REPLIED",2));status(404,()->controller.access("CHAT_ENTRY_RECEIVED",1));
    }
    @Test void wrongTokenMissingSourceUnknownTypeAndNullHashFailClosed(){
        status(404,()->controller.access("CHAT_ENTRY_REPLIED",3));status(404,()->controller.access("CHAT_REPLY_SENT",2));
        status(404,()->controller.access("CHAT_ENTRY_REPLIED",999));status(404,()->controller.access("PAYMENT_SUCCEEDED",1));
        jdbc.update("UPDATE chat_messages SET access_token_hash=null WHERE id=1");status(404,()->controller.access("CHAT_REPLY_SENT",1));
    }
    @Test void onlyNotificationServiceWithWellFormedCapabilityCanUseProof(){
        for(var actor:new Actor[]{Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("CUSTOMER",null,1L,"same@fixture.example"),new Actor("SERVICE","payment-service",null,null)}){
            actor(actor,"C".repeat(43));status(actor.authenticated()?403:401,()->controller.access("CHAT_REPLY_SENT",1));}
        for(String token:new String[]{null,"short","!".repeat(43)}){actor(new Actor("SERVICE","notification-service",null,null),token);status(401,()->controller.access("CHAT_REPLY_SENT",1));}
    }
    @Test void minimalProofDoesNotChangeStoredRowsAndTokenIsRechecked(){
        String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM chat_messages c",String.class);
        assertThat(controller.access("CHAT_ENTRY_REPLIED",1).getBody()).isEqualTo(new ChatNotificationSourceController.Proof("CHAT_ENTRY_REPLIED",1,1,Access.hash("same@fixture.example")));
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(c) ORDER BY id)::text FROM chat_messages c",String.class)).isEqualTo(before);
        jdbc.update("UPDATE chat_messages SET access_token_hash=? WHERE id=1",Access.hash("E".repeat(43)));status(404,()->controller.access("CHAT_ENTRY_REPLIED",1));
    }
    private static void actor(Actor actor,String token){var request=new MockHttpServletRequest();request.setAttribute(Access.ACTOR,actor);if(token!=null)request.addHeader(Access.CHAT_TOKEN,token);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    private static void status(int code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(code));}
}
