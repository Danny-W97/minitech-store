package org.demo.billingservice.observability;

import org.demo.platformsecurity.PersistentQueueMetrics;
import org.demo.platformobservability.RedisStreamMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.demo.platformsecurity.PersistentQueueMetrics.Queue;

@Configuration(proxyBeanMethods = false)
public class BusinessMetricsConfiguration {
    @Bean
    @ConditionalOnProperty(name="messaging.consumer.enabled", havingValue="true", matchIfMissing=true)
    RedisStreamMetrics paymentStreamMetrics(StringRedisTemplate redis,
            @Value("${messaging.payment-stream:payment-events}") String stream,
            @Value("${messaging.consumer.group:billing-service}") String group) {
        return new RedisStreamMetrics(redis, stream, group, RedisStreamMetrics.Recipient.BILLING);
    }

    @Bean PersistentQueueMetrics businessQueueMetrics(JdbcTemplate jdbc) {
        return new PersistentQueueMetrics(jdbc, List.of(
                new Queue("invoice_outbox", "SELECT COUNT(*), MIN(created_at) FROM invoice_outbox_events WHERE status IN ('PENDING','FAILED')")));
    }
}
