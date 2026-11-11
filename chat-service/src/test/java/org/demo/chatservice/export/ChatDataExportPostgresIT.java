package org.demo.chatservice.export;

import org.demo.platformsecurity.Access;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ChatDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("chat_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static JdbcTemplate jdbc;private static ChatDataExportService service;
    private static final String CAPABILITY="C".repeat(43),OTHER="D".repeat(43);
    @BeforeAll static void database(){
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);service=new ChatDataExportService(source);
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE chat_notification_outbox,chat_entries,chat_messages RESTART IDENTITY CASCADE");}
    @Test void capabilitySelectsOnlyItsConversationDespiteIdenticalEmailAndName(){
        long id=conversation(CAPABILITY),other=conversation(OTHER);
        assertThat(service.conversation(id,CAPABILITY).conversation().conversationId()).isEqualTo(id);
        assertThat(service.conversation(other,OTHER).conversation().conversationId()).isEqualTo(other);
        status(404,()->service.conversation(other,CAPABILITY));status(404,()->service.conversation(id,OTHER));
    }
    @Test void storedHeaderTextCopiesAndNullReplyArePreservedWithoutInventingEntries(){
        long id=conversation(CAPABILITY);var result=service.conversation(id,CAPABILITY);
        assertThat(result.conversation().initialMessage()).isEqualTo("Pytanie Żółć");assertThat(result.conversation().latestReply()).isNull();
        assertThat(result.complete()).isFalse();assertThat(result.authorizationMode()).isEqualTo("conversation-capability");
        assertThat(service.entries(id,CAPABILITY,0,50).entries()).isEmpty();
        jdbc.update("UPDATE chat_messages SET reply_message='Odpowiedź Żółć',replied_at=now() WHERE id=?",id);
        assertThat(service.conversation(id,CAPABILITY).conversation().latestReply()).isEqualTo("Odpowiedź Żółć");
        assertThat(service.conversation(id,CAPABILITY).omissions()).contains(new ChatDataExport.Omission("historicalEntries","only-stored-no-reconstruction"));
    }
    @Test void largeMessageHistoryIsBoundedAndContainsOnlyThisConversation(){
        long id=conversation(CAPABILITY),other=conversation(OTHER);entries(id,105);entries(other,2);
        List<Long> ids=new ArrayList<>();long cursor=0;int pages=0;
        while(true){var page=service.entries(id,CAPABILITY,cursor,50);assertThat(page.entries()).hasSizeLessThanOrEqualTo(50);pages++;
            page.entries().forEach(row->ids.add(row.entryId()));if(!page.hasMore()){assertThat(page.nextCursor()).isEqualTo("0");break;}cursor=Long.parseLong(page.nextCursor());}
        assertThat(pages).isEqualTo(3);assertThat(ids).hasSize(105).startsWith(105L,104L).endsWith(1L);assertThat(new HashSet<>(ids)).hasSize(105);
    }
    @Test void storedCustomerAndSupportAuthorsAndInitialFlagAreKept(){
        long id=conversation(CAPABILITY);entries(id,2);var rows=service.entries(id,CAPABILITY,0,50).entries();
        assertThat(rows).extracting(ChatEntryDataExportPage.Entry::author).containsExactly("SUPPORT","CUSTOMER");
        assertThat(rows.getFirst().initialMessage()).isFalse();assertThat(rows.getLast().initialMessage()).isTrue();
        assertThat(rows.getFirst().message()).isEqualTo("Wpis Żółć 2");
    }
    @Test void wrongMissingAndHistoricalUnprotectedConversationsAllReturn404(){
        long id=conversation(OTHER),unprotected=conversation(null);entries(id,1);
        for(long candidate:new long[]{id,unprotected,99999}){status(404,()->service.conversation(candidate,CAPABILITY));status(404,()->service.entries(candidate,CAPABILITY,0,50));}
    }
    @Test void ownEmptyAndExhaustedPagesSucceedWithoutGrantingOtherConversationAccess(){
        long id=conversation(CAPABILITY);assertThat(service.entries(id,CAPABILITY,0,50).entries()).isEmpty();
        entries(id,2);assertThat(service.entries(id,CAPABILITY,1,50).entries()).isEmpty();
    }
    @Test void capabilityIsRevalidatedForEveryPageInTheSameStatement(){
        long id=conversation(CAPABILITY);entries(id,3);var first=service.entries(id,CAPABILITY,0,1);
        jdbc.update("UPDATE chat_messages SET access_token_hash=? WHERE id=?",Access.hash(OTHER),id);
        status(404,()->service.entries(id,CAPABILITY,Long.parseLong(first.nextCursor()),50));
        assertThat(service.entries(id,OTHER,Long.parseLong(first.nextCursor()),50).entries()).hasSize(2);
    }
    @Test void restrictedProjectionDoesNotReadRequestKeysNotificationStatesOrOutbox(){
        long id=conversation(CAPABILITY);entries(id,2);jdbc.execute("CREATE ROLE chat_export LOGIN PASSWORD 'fixture-projection' NOSUPERUSER");jdbc.execute("GRANT USAGE ON SCHEMA public TO chat_export");
        jdbc.execute("GRANT SELECT (id,access_token_hash,customer_name,customer_email,subject,status,message,reply_message,created_at,updated_at,replied_at) ON chat_messages TO chat_export");
        jdbc.execute("GRANT SELECT (id,conversation_id,author,initial_message,message,created_at) ON chat_entries TO chat_export");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"chat_export","fixture-projection");var limited=new JdbcTemplate(restricted);
        for(var sql:List.of("SELECT notification_status FROM chat_messages","SELECT reply_notification_status FROM chat_messages","SELECT request_key FROM chat_entries","SELECT * FROM chat_notification_outbox"))
            assertThatThrownBy(()->limited.queryForList(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new ChatDataExportService(restricted);assertThat(export.conversation(id,CAPABILITY).conversation().conversationId()).isEqualTo(id);
        assertThat(export.entries(id,CAPABILITY,0,50).entries()).hasSize(2);
    }
    @Test void laterMessagesAreNotPresentedAsOneSnapshotAcrossPages(){
        long id=conversation(CAPABILITY);entries(id,3);var first=service.entries(id,CAPABILITY,0,1);
        jdbc.update("INSERT INTO chat_entries(conversation_id,author,initial_message,request_key,message,created_at) VALUES (?,'SUPPORT',false,'later','Nowa odpowiedź',now())",id);
        var next=service.entries(id,CAPABILITY,Long.parseLong(first.nextCursor()),50);
        assertThat(next.entries()).extracting(ChatEntryDataExportPage.Entry::entryId).containsExactly(2L,1L);
        assertThat(next.omissions()).contains(new ChatDataExport.Omission("changesDuringPagination","not-a-snapshot"));
    }
    @Test void downloadsDoNotModifyImmutableHistoryOrCreateNotificationTasksAndIndexExists(){
        long id=conversation(CAPABILITY);entries(id,3);String before=snapshot();service.conversation(id,CAPABILITY);service.entries(id,CAPABILITY,0,50);assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_chat_entries_window'",Integer.class)).isEqualTo(1);
    }
    private long conversation(String token){return jdbc.queryForObject("""
            INSERT INTO chat_messages(customer_name,customer_email,subject,message,status,notification_status,created_at,updated_at,access_token_hash)
            VALUES ('Jan Żółć','same@fixture.example','Temat Żółć','Pytanie Żółć','NEW','fixture-private',now(),now(),?) RETURNING id
            """,Long.class,token==null?null:Access.hash(token));}
    private void entries(long id,int count){jdbc.update("""
            INSERT INTO chat_entries(conversation_id,author,initial_message,request_key,message,created_at)
            SELECT ?,CASE WHEN n%2=1 THEN 'CUSTOMER' ELSE 'SUPPORT' END,n=1,'fixture-private-request-'||n,'Wpis Żółć '||n,now()
            FROM generate_series(1,?) n
            """,id,count);}
    private String snapshot(){return jdbc.queryForObject("SELECT json_build_array((SELECT json_agg(m ORDER BY id) FROM chat_messages m),(SELECT json_agg(e ORDER BY id) FROM chat_entries e),(SELECT json_agg(o ORDER BY entry_id) FROM chat_notification_outbox o))::text",String.class);}
    private void status(int expected,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(expected));}
}
