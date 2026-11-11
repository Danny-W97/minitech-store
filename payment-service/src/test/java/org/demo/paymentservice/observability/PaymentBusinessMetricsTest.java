package org.demo.paymentservice.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.demo.platformsecurity.PersistentQueueMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-metrics-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class PaymentBusinessMetricsTest {

    @Autowired
    private PersistentQueueMetrics businessQueueMetrics;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM payment_refunds");
        jdbcTemplate.execute("DELETE FROM payment_follow_ups");
        jdbcTemplate.execute("DELETE FROM payment_outbox_events");
        jdbcTemplate.execute("DELETE FROM payments");
    }

    private double count(String queue) {
        return meterRegistry.get("platform.business.queue.count")
                .tag("queue", queue)
                .gauge()
                .value();
    }

    private double oldestAge(String queue) {
        return meterRegistry.get("platform.business.queue.oldest.age.seconds")
                .tag("queue", queue)
                .gauge()
                .value();
    }

    private double up(String queue) {
        return meterRegistry.get("platform.business.collector.up")
                .tag("queue", queue)
                .gauge()
                .value();
    }

    @Test
    void allSixQueuesAreRegisteredAndUp() {
        businessQueueMetrics.collect();

        List<String> expectedQueues = List.of(
                "payment_outbox",
                "payment_follow_up",
                "payment_follow_up_dlq",
                "paid_without_invoice",
                "payment_refund",
                "payment_refund_review"
        );

        for (String q : expectedQueues) {
            assertThat(up(q)).as("Collector up for queue: " + q).isEqualTo(1.0);
            assertThat(count(q)).as("Initial count for queue: " + q).isEqualTo(0.0);
            assertThat(oldestAge(q)).as("Initial age for queue: " + q).isEqualTo(0.0);
        }
    }

    @Test
    void paymentRefundTracksRequestedPendingAndUncompletedSucceeded() {
        Instant created = Instant.now().minusSeconds(120);
        insertRefund("ref-1", "pi-1", "REQUESTED", false, null, created);

        businessQueueMetrics.collect();
        assertThat(count("payment_refund")).isEqualTo(1.0);
        assertThat(oldestAge("payment_refund")).isGreaterThanOrEqualTo(120.0);
        assertThat(count("payment_refund_review")).isEqualTo(0.0);

        // Transition to PENDING
        jdbcTemplate.update("UPDATE payment_refunds SET status = 'PENDING' WHERE request_key = 'ref-1'");
        businessQueueMetrics.collect();
        assertThat(count("payment_refund")).isEqualTo(1.0);
        assertThat(oldestAge("payment_refund")).isGreaterThanOrEqualTo(120.0);
        assertThat(count("payment_refund_review")).isEqualTo(0.0);

        // Transition to SUCCEEDED but business_completed = false
        jdbcTemplate.update("UPDATE payment_refunds SET status = 'SUCCEEDED', business_completed = FALSE WHERE request_key = 'ref-1'");
        businessQueueMetrics.collect();
        assertThat(count("payment_refund")).isEqualTo(1.0);
        assertThat(oldestAge("payment_refund")).isGreaterThanOrEqualTo(120.0);
        assertThat(count("payment_refund_review")).isEqualTo(0.0);

        // Business completes
        jdbcTemplate.update("UPDATE payment_refunds SET business_completed = TRUE, business_outcome = 'RESTOCKED' WHERE request_key = 'ref-1'");
        businessQueueMetrics.collect();
        assertThat(count("payment_refund")).isEqualTo(0.0);
        assertThat(oldestAge("payment_refund")).isEqualTo(0.0);
        assertThat(count("payment_refund_review")).isEqualTo(0.0);
    }

    @Test
    void paymentRefundReviewTracksReviewRequiredStatusAndOutcome() {
        Instant created = Instant.now().minusSeconds(200);
        insertRefund("ref-rev-1", "pi-rev-1", "REVIEW_REQUIRED", false, null, created);

        businessQueueMetrics.collect();
        // Excluded from payment_refund, present in payment_refund_review
        assertThat(count("payment_refund")).isEqualTo(0.0);
        assertThat(count("payment_refund_review")).isEqualTo(1.0);
        assertThat(oldestAge("payment_refund_review")).isGreaterThanOrEqualTo(200.0);

        // Another refund with status SUCCEEDED but outcome REVIEW_REQUIRED
        insertRefund("ref-rev-2", "pi-rev-2", "SUCCEEDED", false, "REVIEW_REQUIRED", created);
        businessQueueMetrics.collect();
        assertThat(count("payment_refund")).isEqualTo(0.0);
        assertThat(count("payment_refund_review")).isEqualTo(2.0);

        // Operator resolves ref-rev-1 and completes ref-rev-2
        jdbcTemplate.update("UPDATE payment_refunds SET status = 'SUCCEEDED', business_completed = TRUE, business_outcome = 'MANUAL_RESOLVED' WHERE request_key = 'ref-rev-1'");
        jdbcTemplate.update("UPDATE payment_refunds SET business_completed = TRUE, business_outcome = 'RESOLVED' WHERE request_key = 'ref-rev-2'");
        businessQueueMetrics.collect();
        assertThat(count("payment_refund")).isEqualTo(0.0);
        assertThat(count("payment_refund_review")).isEqualTo(0.0);
    }

    @Test
    void paidWithoutInvoiceTracksPaidOrdersWithoutSuccessfulInvoiceFollowUp() {
        Instant paidTime = Instant.now().minusSeconds(150);
        long orderId = 888L;
        long paymentId = 777L;

        jdbcTemplate.update(
                "INSERT INTO payments (id, order_id, idempotency_key, amount, currency, payment_method, status, provider_declined, stripe_compensation_required, stripe_external_hold, result_recorded_at, created_at, updated_at) " +
                        "VALUES (?, ?, 'idem-pay-1', 99.00, 'PLN', 'CARD', 'SUCCEEDED', false, false, false, ?, ?, ?)",
                paymentId, orderId, Timestamp.from(paidTime), Timestamp.from(paidTime), Timestamp.from(paidTime));

        jdbcTemplate.update(
                "INSERT INTO payment_follow_ups (id, order_id, kind, status, attempts, replay_count, next_attempt_at, created_at, updated_at) " +
                        "VALUES (1001, ?, 'INVOICE_ISSUE', 'PENDING', 0, 0, ?, ?, ?)",
                orderId, Timestamp.from(paidTime), Timestamp.from(paidTime), Timestamp.from(paidTime));

        businessQueueMetrics.collect();
        assertThat(count("paid_without_invoice")).isEqualTo(1.0);
        assertThat(oldestAge("paid_without_invoice")).isGreaterThanOrEqualTo(150.0);

        // Follow-up succeeds
        jdbcTemplate.update("UPDATE payment_follow_ups SET status = 'SUCCEEDED' WHERE id = 1001");
        businessQueueMetrics.collect();
        assertThat(count("paid_without_invoice")).isEqualTo(0.0);
        assertThat(oldestAge("paid_without_invoice")).isEqualTo(0.0);
    }

    private void insertRefund(String key, String intentId, String status, boolean businessCompleted, String businessOutcome, Instant createdAt) {
        long paymentId = Math.abs(key.hashCode()) + 1000L;
        jdbcTemplate.update(
                "INSERT INTO payment_refunds (payment_id, order_id, request_key, operation_id, amount, currency, intent_id, status, automatic, reason, requested_by, business_completed, business_outcome, created_at, updated_at, attempts) " +
                        "VALUES (?, 100, ?, ?, 50.00, 'PLN', ?, ?, true, 'CUSTOMER_REQUEST', 'operator-1', ?, ?, ?, ?, 1)",
                paymentId, key, UUID.randomUUID().toString(), intentId, status, businessCompleted, businessOutcome, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }
}
