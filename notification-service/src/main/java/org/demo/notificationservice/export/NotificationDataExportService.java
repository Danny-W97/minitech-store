package org.demo.notificationservice.export;

import org.demo.platformsecurity.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class NotificationDataExportService {
    private static final List<String> ORDER_TYPES=List.of("ORDER_CREATED","ORDER_STATUS_CHANGED","PAYMENT_PENDING","PAYMENT_SUCCEEDED","PAYMENT_FAILED");
    private static final String QUERY="""
            SELECT id,event_type,source_id,recipient,channel,subject,message,status,content_compacted_at,created_at,updated_at,sent_at
            FROM notifications WHERE source_id=:id AND event_type IN (:types) AND id<=:ceiling
              AND (:recipientHash='' OR encode(sha256(convert_to(recipient,'UTF8')),'hex')=:recipientHash)
            ORDER BY id DESC LIMIT :limit
            """;
    private static final List<NotificationDataExportPage.Omission> OMISSIONS=List.of(
            new NotificationDataExportPage.Omission("accountSecurityNotifications","manual-source-proof-no-security-links"),
            new NotificationDataExportPage.Omission("supportDirectedChatNotifications","excluded-user-decision"),
            new NotificationDataExportPage.Omission("chatRecipientMismatch","excluded-after-capability-and-source-proof"),
            new NotificationDataExportPage.Omission("inboxAndDeadLetters","covered-domain-data-operational-excluded-manual-review"),
            new NotificationDataExportPage.Omission("deliveryDiagnosticsAndProviderReferences","excluded"),
            new NotificationDataExportPage.Omission("unknownEventTypes","excluded-unproven-source"),
            new NotificationDataExportPage.Omission("recipientAndText","as-stored-no-semantic-redaction-not-ownership-proof"),
            new NotificationDataExportPage.Omission("changesDuringPagination","not-a-snapshot"),
            new NotificationDataExportPage.Omission("otherAccountData","pending"));
    private final NamedParameterJdbcTemplate jdbc;
    private final OrderAccess orders;
    private final NotificationChatAccess chat;
    public NotificationDataExportService(DataSource source,OrderAccess orders,NotificationChatAccess chat) {
        var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);
        this.orders=orders;this.chat=chat;
    }
    public NotificationDataExportPage order(long id,long cursor,int size) {
        validate(cursor,size);orders.requireCustomerOwner(id);
        return page("order-notifications","customer-order-owner",id,null,ORDER_TYPES,"",cursor,size);
    }
    public NotificationDataExportPage chat(String eventType,long id,String capability,long cursor,int size) {
        validate(cursor,size);
        // Explicit human choice: only customer-directed replies; no support mailbox metadata.
        if(!List.of("CHAT_REPLY_SENT","CHAT_ENTRY_REPLIED").contains(eventType))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification source not found");
        var proof=chat.require(eventType,id,capability);
        return page("chat-source-notifications","conversation-capability",id,proof.conversationId(),List.of(eventType),proof.recipientHash(),cursor,size);
    }
    private NotificationDataExportPage page(String scope,String authorization,long id,Long conversation,List<String> types,String recipientHash,long cursor,int size) {
        var window=new ListWindow(cursor,size);
        var rows=jdbc.query(QUERY,Map.of("id",id,"types",types,"recipientHash",recipientHash,"ceiling",window.ceiling(),"limit",size+1),
                (rs,n)->new NotificationDataExportPage.Entry(rs.getLong("id"),rs.getString("event_type"),rs.getLong("source_id"),
                        rs.getString("recipient"),rs.getString("channel"),rs.getString("subject"),rs.getString("message"),
                        rs.getString("status"),rs.getTimestamp("content_compacted_at")==null?"RETAINED":"COMPACTED",
                        instant(rs,"content_compacted_at"),instant(rs,"created_at"),instant(rs,"updated_at"),instant(rs,"sent_at")));
        var page=window.map(rows,NotificationDataExportPage.Entry::notificationId,Function.identity());
        return new NotificationDataExportPage(1,scope,false,Instant.now(),authorization,id,conversation,page.items(),page.hasMore(),page.nextCursor(),OMISSIONS);
    }
    private static Instant instant(ResultSet row,String name)throws SQLException{return row.getTimestamp(name)==null?null:row.getTimestamp(name).toInstant();}
    private static void validate(long cursor,int size) {
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
    }
}
