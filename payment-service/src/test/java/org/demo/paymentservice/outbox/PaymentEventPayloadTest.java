package org.demo.paymentservice.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentEventPayloadTest {
    @Test void modernIdentityIsRandomDurableAndKeepsTheExactLegacyBinding() {
        var event=new PaymentOutboxEvent("PAYMENT_SUCCEEDED",1L,BigDecimal.TEN,"fixture@example.test");
        var oldKey=event.getEventKey();var oldId=event.getEventId();event.useNewIdentity();var id=event.getEventId();
        assertThat(id).matches("payment-v2-[0-9a-f-]{36}");assertThat(event.getEventKey()).isEqualTo("v2:"+org.demo.platformevents.EventIdentity.hash(oldKey));
        assertThat(event.getLegacyEventId()).isEqualTo(oldId);assertThat(event.getEventKey()).doesNotContain("fixture@example.test");
        event.markFailed("fixture retry");event.markPublished();assertThat(event.getEventId()).isEqualTo(id);
        assertThatThrownBy(event::useNewIdentity).isInstanceOf(IllegalStateException.class);
    }
    @Test void persistedLegacyBacklogCannotBeRekeyed() {
        var event=new PaymentOutboxEvent("PAYMENT_SUCCEEDED",1L,BigDecimal.TEN,"fixture@example.test");var id=event.getEventId();
        ReflectionTestUtils.setField(event,"id",1L);assertThatThrownBy(event::useNewIdentity).isInstanceOf(IllegalStateException.class);
        assertThat(event.getEventId()).isEqualTo(id);assertThat(event.getIdentityVersion()).isEqualTo(1);
    }
    @Test void compactedPayloadIsRejectedBeforeAnyBrokerCall() {
        var event=new PaymentOutboxEvent("PAYMENT_SUCCEEDED",1L,BigDecimal.TEN,"fixture@example.test","fixture-address");
        ReflectionTestUtils.setField(event,"payloadCompactedAt",Instant.now());
        var redis=mock(StringRedisTemplate.class);
        assertThatThrownBy(()->new PaymentEventPublisher(redis,"unused-fixture").publish(event))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("compacted");
        verifyNoInteractions(redis);
    }
    @Test void fullPendingAndPublishedPayloadsKeepTheirExistingRepresentation() {
        var event=new PaymentOutboxEvent("PAYMENT_SUCCEEDED",1L,BigDecimal.TEN,"fixture@example.test","fixture-address");
        String key=event.getEventKey(),id=event.getEventId();event.requirePublishablePayload();event.markPublished();event.requirePublishablePayload();
        assertThat(event.isPayloadCompacted()).isFalse();assertThat(event.getRecipient()).isEqualTo("fixture@example.test");
        assertThat(event.getDeliveryAddress()).isEqualTo("fixture-address");assertThat(event.getEventKey()).isEqualTo(key);assertThat(event.getEventId()).isEqualTo(id);
    }
}
