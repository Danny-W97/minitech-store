package org.demo.orderservice.observability;

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
                new Queue("order_saga", "SELECT COUNT(*), MIN(created_at) FROM order_sagas WHERE state IN ('INVENTORY_RESERVATION_PENDING','COMPENSATION_PENDING')"),
                new Queue("order_reconciliation", "SELECT COUNT(*), MIN(created_at) FROM order_sagas WHERE state = 'RECONCILIATION_REQUIRED'")));
    }
}
