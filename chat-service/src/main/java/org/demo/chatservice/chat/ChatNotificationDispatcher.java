package org.demo.chatservice.chat;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.demo.platformsecurity.BoundedJobs;

@Component
@ConditionalOnProperty(name="chat.outbox.enabled",havingValue="true",matchIfMissing=true)
public class ChatNotificationDispatcher implements AutoCloseable {
    private final ChatNotificationStateStore states;
    private final ChatNotificationClient client;
    private final BoundedJobs jobs=new BoundedJobs("chat-notification",2,50,8000);
    public ChatNotificationDispatcher(ChatNotificationStateStore states,ChatNotificationClient client) { this.states=states;this.client=client; }
    @Scheduled(fixedDelayString="${chat.outbox.fixed-delay-ms:1000}")
    public void dispatch() { states.due().forEach(id -> jobs.submit(id,() -> {
        try { deliver(id); } catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Chat notification task {} will recover after its lease",id);
        }
    })); }
    public void deliver(Long id) {
        var ticket=states.claim(id);if(ticket==null)return;
        ChatNotificationClient.Receipt receipt;
        try { receipt=client.send(ticket); } catch(RuntimeException failure) { receipt=null; }
        states.complete(ticket,receipt);
    }
    @Override public void close(){jobs.close();}
}
