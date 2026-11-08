package org.demo.chatservice.export;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.ListWindow;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
public class ChatDataExportService {
    private static final String HEADER="""
            SELECT id,customer_name,customer_email,subject,status,message,reply_message,created_at,updated_at,replied_at
            FROM chat_messages WHERE id=:conversationId AND access_token_hash=:tokenHash
            """;
    private static final String ENTRIES="""
            WITH owned AS MATERIALIZED (
              SELECT id FROM chat_messages WHERE id=:conversationId AND access_token_hash=:tokenHash
            )
            SELECT o.id AS owned_conversation_id,e.id,e.author,e.initial_message,e.message,e.created_at
            FROM owned o LEFT JOIN LATERAL (
              SELECT id,author,initial_message,message,created_at FROM chat_entries
              WHERE conversation_id=o.id AND id<=:ceiling AND author IN ('CUSTOMER','SUPPORT') ORDER BY id DESC LIMIT :limit
            ) e ON TRUE ORDER BY e.id DESC
            """;
    private static final List<ChatDataExport.Omission> OMISSIONS=List.of(
            new ChatDataExport.Omission("privateNotes","not-stored-no-reconstruction"),
            new ChatDataExport.Omission("accountAssociation","not-stored-not-inferred"),
            new ChatDataExport.Omission("conversationEntries","separate-endpoint"),
            new ChatDataExport.Omission("capabilityAndRequestKeys","excluded"),
            new ChatDataExport.Omission("notificationWorkflow","excluded"),
            new ChatDataExport.Omission("submittedText","as-stored-no-semantic-redaction"),
            new ChatDataExport.Omission("historicalEntries","only-stored-no-reconstruction"),
            new ChatDataExport.Omission("otherAccountData","pending"),
            new ChatDataExport.Omission("changesDuringPagination","not-a-snapshot"));
    private static final List<ChatDataExport.Omission> PAGE_OMISSIONS=List.of(
            new ChatDataExport.Omission("privateNotes","not-stored-no-reconstruction"),
            new ChatDataExport.Omission("accountAssociation","not-stored-not-inferred"),
            new ChatDataExport.Omission("conversationHeader","separate-endpoint"),
            new ChatDataExport.Omission("capabilityAndRequestKeys","excluded"),
            new ChatDataExport.Omission("notificationWorkflow","excluded"),
            new ChatDataExport.Omission("submittedText","as-stored-no-semantic-redaction"),
            new ChatDataExport.Omission("historicalEntries","only-stored-no-reconstruction"),
            new ChatDataExport.Omission("changesDuringPagination","not-a-snapshot"));
    private final NamedParameterJdbcTemplate jdbc;
    public ChatDataExportService(DataSource source) {
        var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);
    }
    public ChatDataExport conversation(long conversationId,String capability) {
        var hash=proof(conversationId,capability);
        var rows=jdbc.query(HEADER,Map.of("conversationId",conversationId,"tokenHash",hash),(rs,row)->
                new ChatDataExport.Conversation(rs.getLong("id"),rs.getString("customer_name"),rs.getString("customer_email"),
                        rs.getString("subject"),rs.getString("status"),rs.getString("message"),rs.getString("reply_message"),
                        rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("replied_at")==null?null:rs.getTimestamp("replied_at").toInstant()));
        if(rows.isEmpty())throw missing();
        return new ChatDataExport(1,"chat-conversation",false,Instant.now(),"conversation-capability",rows.getFirst(),OMISSIONS);
    }
    public ChatEntryDataExportPage entries(long conversationId,String capability,long cursor,int size) {
        var hash=proof(conversationId,capability);
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
        var window=new ListWindow(cursor,size);
        List<EntryRow> rows=jdbc.query(ENTRIES,Map.of("conversationId",conversationId,"tokenHash",hash,"ceiling",window.ceiling(),"limit",size+1),
                (rs,row)->new EntryRow(rs.getObject("id")==null?null:new ChatEntryDataExportPage.Entry(rs.getLong("id"),rs.getString("author"),
                        rs.getBoolean("initial_message"),rs.getString("message"),rs.getTimestamp("created_at").toInstant())));
        if(rows.isEmpty())throw missing();
        var page=window.map(rows.stream().map(EntryRow::entry).filter(Objects::nonNull).toList(),ChatEntryDataExportPage.Entry::entryId,Function.identity());
        return new ChatEntryDataExportPage(1,"chat-entries-page",false,Instant.now(),"conversation-capability",conversationId,
                page.items(),page.hasMore(),page.nextCursor(),PAGE_OMISSIONS);
    }
    private record EntryRow(ChatEntryDataExportPage.Entry entry) {}
    private static String proof(long id,String capability) {
        if(capability==null||!capability.matches("[A-Za-z0-9_-]{43,128}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Conversation access token required");
        if(id<1)throw missing();
        return Access.hash(capability);
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation not found");}
}
