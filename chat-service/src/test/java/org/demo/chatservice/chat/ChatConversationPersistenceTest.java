package org.demo.chatservice.chat;
import org.demo.chatservice.web.*;
import org.demo.platformsecurity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a38;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","chat.outbox.enabled=false","spring.docker.compose.enabled=false"})
class ChatConversationPersistenceTest {
    @Autowired ChatMessageService service;
    @Autowired ChatNotificationStateStore states;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    MockHttpServletRequest request;
    @BeforeEach void setup(){
        jdbc.execute("DELETE FROM chat_notification_outbox");jdbc.execute("DELETE FROM chat_entries");jdbc.execute("DELETE FROM chat_messages");
        request=new MockHttpServletRequest();request.addHeader(Access.CHAT_TOKEN,"C".repeat(43));
        request.setAttribute(Access.ACTOR,new Actor("OPERATOR",null,null,null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    ChatMessageResponse create(){return service.create(new CreateChatMessageRequest("Guest","guest@example.test","Subject","Question"));}
    @Test void immutableHistoryAndIdempotentReplyHaveSeparateNotificationTasks(){
        var root=create();service.reply(root.id(),new ReplyChatMessageRequest("First","one"));
        service.reply(root.id(),new ReplyChatMessageRequest("Second","two"));service.reply(root.id(),new ReplyChatMessageRequest("First","one"));
        assertThat(service.history(root.id(),0,100).entries()).extracting(ChatEntryResponse::message).containsExactly("Question","First","Second");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_notification_outbox",Long.class)).isEqualTo(3);
        assertThatThrownBy(()->service.reply(root.id(),new ReplyChatMessageRequest("Changed","one"))).isInstanceOf(ChatException.class);
        var latest=service.history(root.id(),0,1);assertThat(latest.hasMore()).isTrue();
        assertThat(service.history(root.id(),latest.nextCursor(),2).entries()).extracting(ChatEntryResponse::message).containsExactly("Question","First");
        request.setAttribute(Access.ACTOR,Actor.ANONYMOUS);request.removeHeader(Access.CHAT_TOKEN);
        assertThatThrownBy(()->service.history(root.id(),0,50)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void entryAndTaskRollBackTogether(){
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{create();throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_entries",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_notification_outbox",Long.class)).isZero();
    }
    @Test void expiredLeaseCannotOverwriteTheNewOwnerAndAcceptedFailureIsNotReplayed(){
        var root=create();var old=states.claim(root.lastEntryId());
        jdbc.update("UPDATE chat_notification_outbox SET processing_until=? WHERE entry_id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)),root.lastEntryId());
        var current=states.claim(root.lastEntryId());assertThat(current.token()).isNotEqualTo(old.token());
        states.complete(old,new ChatNotificationClient.Receipt(1L,"SENT"));
        assertThat(jdbc.queryForObject("SELECT status FROM chat_notification_outbox WHERE entry_id=?",String.class,root.lastEntryId())).isEqualTo("PENDING");
        states.complete(current,new ChatNotificationClient.Receipt(2L,"FAILED"));
        assertThat(states.claim(root.lastEntryId())).isNull();
        assertThatThrownBy(()->states.replay(root.id(),root.lastEntryId())).isInstanceOf(ChatException.class);
    }
}
