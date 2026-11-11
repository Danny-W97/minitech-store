package org.demo.notificationservice.notification;

import org.demo.notificationservice.web.NotificationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationContentTest {
    private Notification full() {
        return new Notification(NotificationEventType.PAYMENT_SUCCEEDED,12L,"fixture@example.test",NotificationChannel.EMAIL,
                "Fixture subject","Fixture message");
    }
    private Notification compacted() {
        var n=full();n.markSent("FIXTURE-SENT");
        ReflectionTestUtils.setField(n,"subject",null);ReflectionTestUtils.setField(n,"message",null);
        ReflectionTestUtils.setField(n,"contentCompactedAt",Instant.now());return n;
    }
    @Test void normalResponsesKeepTheirContentAndReportNoCompaction() {
        var dto=NotificationResponse.from(full());
        assertThat(dto.subject()).isEqualTo("Fixture subject");assertThat(dto.message()).isEqualTo("Fixture message");
        assertThat(dto.contentCompacted()).isFalse();
    }
    @Test void compactedResponseIsExplicitAndNeverInventsTheOriginalContent() {
        var dto=NotificationResponse.from(compacted());
        assertThat(dto.status()).isEqualTo(NotificationStatus.SENT);assertThat(dto.providerReference()).isEqualTo("FIXTURE-SENT");
        assertThat(dto.contentCompacted()).isTrue();assertThat(dto.subject()).isEqualTo("Content removed");
        assertThat(dto.message()).isEqualTo("Content removed by retention policy.");
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(dto)).contains("\"contentCompacted\":true");
    }
    @Test void compactedNotificationCannotReachDeliveryOrAttachmentClients() {
        var sender=mock(JavaMailSender.class);var pdf=mock(BillingPdfClient.class);var n=compacted();
        var smtp=new SmtpNotificationDeliveryService(sender,pdf,"fixture@example.test");
        assertThatThrownBy(()->smtp.deliver(n,false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("compacted");
        assertThatThrownBy(()->smtp.deliver(n,true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ExternalApiNotificationDeliveryService().deliver(n,false)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sender,pdf);
    }
    @Test void compactedTerminalRecordCannotBeRequeuedOrRewrittenThroughEntityMethods() {
        var n=compacted();
        assertThatThrownBy(()->n.prepareForRetry("other@example.test",NotificationChannel.EMAIL,"restored","restored")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(n::startDeliveryAttempt).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(n::queueForDelivery).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->n.claim("fixture",Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->n.markFailed("restored",null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->n.markSent("restored")).isInstanceOf(IllegalStateException.class);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);assertThat(n.getMessage()).isNull();assertThat(n.getSubject()).isNull();
        assertThat(n.getRecipient()).isEqualTo("fixture@example.test");assertThat(n.getProviderReference()).isEqualTo("FIXTURE-SENT");
    }
}
