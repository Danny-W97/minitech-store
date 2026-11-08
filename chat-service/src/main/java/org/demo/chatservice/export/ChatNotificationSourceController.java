package org.demo.chatservice.export;

import org.demo.platformsecurity.Access;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.util.Map;

/** Minimal capability proof. Only a recipient digest leaves Chat, never the raw address, text or token. */
@RestController
@RequestMapping("/internal/chat/notification-sources")
public class ChatNotificationSourceController {
    public record Proof(String eventType,long sourceId,long conversationId,String recipientHash) {}
    private final NamedParameterJdbcTemplate jdbc;
    public ChatNotificationSourceController(DataSource source) {
        var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);
    }
    @GetMapping("/{eventType}/{sourceId}/access")
    public ResponseEntity<Proof> access(@PathVariable String eventType,@PathVariable long sourceId) {
        var actor=Access.actor();Access.require(actor.internal() && "notification-service".equals(actor.service()));
        String token=Access.header(Access.CHAT_TOKEN);
        if(token==null||!token.matches("[A-Za-z0-9_-]{43,128}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Conversation access token required");
        String sql=switch(eventType) {
            case "CHAT_REPLY_SENT" -> "SELECT id,customer_email FROM chat_messages WHERE id=:id AND access_token_hash=:hash";
            case "CHAT_ENTRY_REPLIED" -> """
                    SELECT c.id,c.customer_email FROM chat_entries e JOIN chat_messages c ON c.id=e.conversation_id
                    WHERE e.id=:id AND c.access_token_hash=:hash AND e.author='SUPPORT'
                    """;
            default -> throw missing();
        };
        var rows=jdbc.query(sql,Map.of("id",sourceId,"hash",Access.hash(token)),
                (rs,n)->new Proof(eventType,sourceId,rs.getLong("id"),Access.hash(rs.getString("customer_email"))));
        if(rows.isEmpty())throw missing();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(rows.getFirst());
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification source not found");}
}
