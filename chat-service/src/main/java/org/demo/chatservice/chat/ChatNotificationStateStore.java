package org.demo.chatservice.chat;
import org.demo.platformsecurity.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.demo.chatservice.web.ChatException;
import java.time.Instant;
import java.util.*;

@Service
public class ChatNotificationStateStore {
    public record Ticket(Long entryId,Long conversationId,String token,String eventType,Long sourceId,
                         String author,String text,String name,String email,String subject) {}
    private final ChatNotificationTaskRepository tasks;
    private final ChatEntryRepository entries;
    private final ChatMessageRepository messages;
    private final long lease;
    private final int maximum;
    public ChatNotificationStateStore(ChatNotificationTaskRepository tasks,ChatEntryRepository entries,ChatMessageRepository messages,
            @Value("${chat.outbox.lease-ms:30000}") long lease,@Value("${chat.outbox.max-attempts:20}") int maximum) {
        if(lease<10000 || lease>3600000 || maximum<1 || maximum>100) throw new IllegalArgumentException("Invalid chat outbox settings");
        this.tasks=tasks;this.entries=entries;this.messages=messages;this.lease=lease;this.maximum=maximum;
    }
    @Transactional(readOnly=true) public List<Long> due() {
        return tasks.due(Instant.now(),org.springframework.data.domain.PageRequest.of(0,50));
    }
    @Transactional public Ticket claim(Long id) {
        var task=tasks.lockId(id).orElseThrow();
        if(!task.claimable()) return null;
        var entry=entries.findById(id).orElseThrow();var root=messages.findById(entry.getConversationId()).orElseThrow();
        String token=UUID.randomUUID().toString();task.claim(token,lease);
        return new Ticket(id,root.getId(),token,task.getEventType(),task.getSourceId(),entry.getAuthor(),entry.getMessage(),root.getCustomerName(),root.getCustomerEmail(),root.getSubject());
    }
    @Transactional public void complete(Ticket ticket,ChatNotificationClient.Receipt receipt) {
        var task=tasks.lockId(ticket.entryId()).orElseThrow();
        if(!ticket.token().equals(task.getProcessingToken())) return;
        if(receipt!=null) task.complete(receipt.id(),receipt.status()); else task.fail(maximum);
        var root=messages.lockId(ticket.conversationId()).orElseThrow();
        var entry=entries.findById(ticket.entryId()).orElseThrow();
        if(entry.isInitialMessage()) root.markNotification(task.publicStatus());
        else if(Objects.equals(root.getReplyEntryId(),ticket.entryId())) root.markReplyNotification(task.publicStatus());
    }
    @Transactional public void replay(Long conversation,Long entryId) {
        Access.require(Access.actor().operator());
        var entry=entries.findById(entryId).orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND,"Nie znaleziono wpisu."));
        if(!conversation.equals(entry.getConversationId())) throw new ChatException(HttpStatus.NOT_FOUND,"Nie znaleziono wpisu.");
        var task=tasks.lockId(entryId).orElseThrow();
        if(!"DEAD_LETTER".equals(task.getStatus())) throw new ChatException(HttpStatus.CONFLICT,"Ponowienie dotyczy wyłącznie nieprzekazanego zadania DEAD_LETTER.");
        task.replay();
    }
}
