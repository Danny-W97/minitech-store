package org.demo.paymentservice.outbox;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a32outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","outbox.publisher.enabled=false"})
class PaymentOutboxLeaseTest {
    @Autowired PaymentOutboxRepository repository;
    @Autowired PaymentOutboxStateStore states;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clear() { repository.deleteAll(); }
    @Test void onlyOneWorkerClaimsAnEventAndTheOldWorkerCannotOverwriteItsSuccessor() {
        var event=repository.saveAndFlush(new PaymentOutboxEvent("PAYMENT_SUCCEEDED",7L,BigDecimal.TEN,"owner@example.test"));
        var first=states.claim(event.getId());assertThat(first).isNotNull();assertThat(states.claim(event.getId())).isNull();
        jdbc.update("update payment_outbox_events set processing_until=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),event.getId());
        var second=states.claim(event.getId());assertThat(second.token()).isNotEqualTo(first.token());
        states.complete(first,true);
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
        states.complete(second,false);var failed=repository.findById(event.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(states.claim(event.getId())).isNull();
        jdbc.update("update payment_outbox_events set next_attempt_at=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),event.getId());
        var retry=states.claim(event.getId());states.complete(retry,true);
        assertThat(states.claim(event.getId())).isNull();
        assertThat(retry.event().getEventId()).isEqualTo(first.event().getEventId());
    }
}
