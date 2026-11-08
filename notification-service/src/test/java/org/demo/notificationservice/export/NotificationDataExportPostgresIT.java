package org.demo.notificationservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class NotificationDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("export_fixture").withUsername("fixture").withPassword("fixture-only");
    private static JdbcTemplate jdbc;private static DriverManagerDataSource source;private OrderAccess orders;private NotificationChatAccess chat;private NotificationDataExportService service;
    @BeforeAll static void database(){source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE notifications RESTART IDENTITY CASCADE");orders=mock(OrderAccess.class);chat=mock(NotificationChatAccess.class);service=new NotificationDataExportService(source,orders,chat);}
    @Test void chatReplyExportsUseExactTypeAndRevalidateCapabilityOnEveryPage(){
        insert(7,"CHAT_ENTRY_REPLIED",3);insert(7,"CHAT_REPLY_SENT",1);insert(7,"CHAT_MESSAGE_RECEIVED",1);insert(7,"PAYMENT_SUCCEEDED",1);
        when(chat.require("CHAT_ENTRY_REPLIED",7,"C".repeat(43))).thenReturn(new NotificationChatAccess.Proof("CHAT_ENTRY_REPLIED",7,9,org.demo.platformsecurity.Access.hash("same1@fixture.example")));
        var first=service.chat("CHAT_ENTRY_REPLIED",7,"C".repeat(43),0,1);
        assertThat(first.conversationId()).isEqualTo(9);assertThat(first.items()).extracting(NotificationDataExportPage.Entry::eventType).containsExactly("CHAT_ENTRY_REPLIED");
        assertThat(first.items()).extracting(NotificationDataExportPage.Entry::recipient).containsExactly("same1@fixture.example");
        assertThat(first.hasMore()).isFalse();
        assertThat(service.chat("CHAT_ENTRY_REPLIED",7,"C".repeat(43),1,50).items()).isEmpty();
        verify(chat,times(2)).require("CHAT_ENTRY_REPLIED",7,"C".repeat(43));verifyNoInteractions(orders);
    }
    @Test void authorizedChatWithoutStoredNotificationReturnsAnEmptyPage(){
        when(chat.require("CHAT_REPLY_SENT",7,"C".repeat(43))).thenReturn(new NotificationChatAccess.Proof("CHAT_REPLY_SENT",7,7,org.demo.platformsecurity.Access.hash("same1@fixture.example")));
        assertThat(service.chat("CHAT_REPLY_SENT",7,"C".repeat(43),0,50).items()).isEmpty();
    }
    @Test void typeAndSourceGraphPreventNumericalIdAndRecipientCollisions(){
        insert(7,"PAYMENT_SUCCEEDED",1);insert(8,"PAYMENT_SUCCEEDED",1);
        for(String type:List.of("CUSTOMER_REGISTERED","PASSWORD_RESET_REQUESTED","CHAT_ENTRY_REPLIED","CHAT_REPLY_SENT"))insert(7,type,1);
        assertThat(service.order(7,0,50).items()).hasSize(1).allSatisfy(e->{assertThat(e.eventType()).isEqualTo("PAYMENT_SUCCEEDED");assertThat(e.sourceId()).isEqualTo(7);});
        verify(orders).requireCustomerOwner(7L);
    }
    @Test void cursorBoundsLargeHistoryWithoutDuplicatesAndRechecksOwnership(){
        insert(7,"PAYMENT_PENDING",105);var ids=new HashSet<Long>();long cursor=0;int pages=0;
        while(true){var page=service.order(7,cursor,50);pages++;assertThat(page.items()).hasSizeLessThanOrEqualTo(50);
            page.items().forEach(e->assertThat(ids.add(e.notificationId())).isTrue());if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).hasSize(105);assertThat(pages).isEqualTo(3);verify(orders,times(3)).requireCustomerOwner(7L);
    }
    @Test void compactedContentIsExplicitAndRetainedContentPreserved(){
        insert(7,"PAYMENT_SUCCEEDED",2);jdbc.update("UPDATE notifications SET subject=null,message=null,content_compacted_at=now(),status='SENT',sent_at=now() WHERE id=2");
        var page=service.order(7,0,50);assertThat(page.complete()).isFalse();assertThat(page.items().getFirst().contentDisposition()).isEqualTo("COMPACTED");
        assertThat(page.items().getFirst().message()).isNull();assertThat(page.items().getLast().message()).isEqualTo("Wiadomość Żółć");
    }
    @Test void emptyAndExhaustedOwnedSourceRemainEmpty(){assertThat(service.order(7,0,50).items()).isEmpty();insert(7,"ORDER_CREATED",1);assertThat(service.order(7,1,50).items()).isEmpty();}
    @Test void downloadsAreReadOnlyAndExistingSourceIndexIsPresent(){
        insert(7,"ORDER_STATUS_CHANGED",2);String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(n) ORDER BY id)::text FROM notifications n",String.class);
        service.order(7,0,50);assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(n) ORDER BY id)::text FROM notifications n",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_notifications_source_window'",Integer.class)).isEqualTo(1);
    }
    @Test void projectionWorksWithoutAccessToDiagnosticsOrOtherNotificationTables(){
        insert(7,"PAYMENT_SUCCEEDED",1);jdbc.execute("CREATE ROLE export_reader LOGIN PASSWORD 'fixture-only-reader' NOSUPERUSER");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO export_reader");
        jdbc.execute("GRANT SELECT(id,event_type,source_id,recipient,channel,subject,message,status,content_compacted_at,created_at,updated_at,sent_at) ON notifications TO export_reader");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"export_reader","fixture-only-reader");var limited=new JdbcTemplate(restricted);
        for(String query:List.of("SELECT provider_reference FROM notifications","SELECT failure_reason FROM notifications","SELECT processing_token FROM notifications","SELECT * FROM event_inbox","SELECT * FROM notification_dead_letters"))
            assertThatThrownBy(()->limited.queryForList(query)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new NotificationDataExportService(restricted,orders,mock(NotificationChatAccess.class));assertThat(export.order(7,0,50).items()).hasSize(1);
    }
    private void insert(long sourceId,String type,int count){jdbc.update("""
            INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at)
            SELECT ?,?,concat('same',g,'@fixture.example'),'EMAIL','Temat Żółć','Wiadomość Żółć','SENT',now(),now()
            FROM generate_series(1,?) g
            """,type,sourceId,count);}
}
