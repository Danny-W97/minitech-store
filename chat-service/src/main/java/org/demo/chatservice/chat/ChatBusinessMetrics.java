package org.demo.chatservice.chat;
import org.demo.platformsecurity.PersistentQueueMetrics;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
@Configuration(proxyBeanMethods=false)
public class ChatBusinessMetrics {
    @Bean PersistentQueueMetrics chatQueueMetrics(JdbcTemplate jdbc) {
        return new PersistentQueueMetrics(jdbc,List.of(
                new PersistentQueueMetrics.Queue("chat_notification","SELECT COUNT(*), MIN(created_at) FROM chat_notification_outbox WHERE status IN ('PENDING','RETRY')"),
                new PersistentQueueMetrics.Queue("chat_notification_dlq","SELECT COUNT(*), MIN(created_at) FROM chat_notification_outbox WHERE status='DEAD_LETTER'")));
    }
}
