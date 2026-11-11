package org.demo.paymentservice.observability;

import org.demo.platformsecurity.PersistentQueueMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.demo.platformsecurity.PersistentQueueMetrics.Queue;

@Configuration(proxyBeanMethods = false)
public class BusinessMetricsConfiguration {
    @Bean PersistentQueueMetrics businessQueueMetrics(JdbcTemplate jdbc) {
        return new PersistentQueueMetrics(jdbc, List.of(
                new Queue("payment_outbox", "SELECT COUNT(*), MIN(created_at) FROM payment_outbox_events WHERE status IN ('PENDING','FAILED')"),
                new Queue("payment_follow_up", "SELECT COUNT(*), MIN(created_at) FROM payment_follow_ups WHERE status IN ('PENDING','RETRY')"),
                new Queue("payment_follow_up_dlq", "SELECT COUNT(*), MIN(created_at) FROM payment_follow_ups WHERE status = 'DEAD_LETTER'"),
                new Queue("paid_without_invoice", "SELECT COUNT(*), MIN(COALESCE(p.result_recorded_at,p.updated_at)) FROM payments p JOIN payment_follow_ups f ON f.order_id = p.order_id AND f.kind = 'INVOICE_ISSUE' WHERE p.status = 'SUCCEEDED' AND f.status <> 'SUCCEEDED'"),
                new Queue("payment_refund", "SELECT COUNT(*), MIN(created_at) FROM payment_refunds WHERE (status IN ('REQUESTED','PENDING') OR (status = 'SUCCEEDED' AND business_completed = FALSE)) AND (business_outcome IS NULL OR business_outcome <> 'REVIEW_REQUIRED')"),
                new Queue("payment_refund_review", "SELECT COUNT(*), MIN(created_at) FROM payment_refunds WHERE status = 'REVIEW_REQUIRED' OR business_outcome = 'REVIEW_REQUIRED'")));
    }
}
