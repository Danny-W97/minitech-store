package org.demo.notificationservice.notification;

import org.demo.notificationservice.web.NotificationDeadLetterResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class DeadLetterContentTest {
    private Notification failed() {
        var n=new Notification(NotificationEventType.PAYMENT_SUCCEEDED,12L,"fixture@example.test",NotificationChannel.EMAIL,"Fixture subject","Fixture body");
        n.markFailed("Fixture reason",null);return n;
    }
    private NotificationDeadLetterEntry compacted() {
        var d=new NotificationDeadLetterEntry(failed());d.markResolved();
        ReflectionTestUtils.setField(d,"subject",null);ReflectionTestUtils.setField(d,"message",null);ReflectionTestUtils.setField(d,"failureReason",null);
        ReflectionTestUtils.setField(d,"contentCompactedAt",Instant.now());return d;
    }
    @Test void openResponsesKeepCompleteReplayData() {
        var dto=NotificationDeadLetterResponse.from(new NotificationDeadLetterEntry(failed()));
        assertThat(dto.contentCompacted()).isFalse();assertThat(dto.subject()).isEqualTo("Fixture subject");
        assertThat(dto.message()).isEqualTo("Fixture body");assertThat(dto.failureReason()).isEqualTo("Fixture reason");
    }
    @Test void compactedResolvedDtoIsExplicitAndSerializable() {
        var dto=NotificationDeadLetterResponse.from(compacted());assertThat(dto.contentCompacted()).isTrue();assertThat(dto.status()).isEqualTo(DeadLetterStatus.RESOLVED);
        assertThat(dto.subject()).isEqualTo("Content removed");assertThat(dto.message()).isEqualTo("Content removed by retention policy.");
        assertThat(dto.failureReason()).isEqualTo("Diagnostic removed by retention policy.");
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(dto)).contains("\"contentCompacted\":true");
    }
    @Test void compactedEntryCannotBeReopenedAndRepeatedResolutionKeepsItsTime() {
        var d=compacted();Instant resolved=d.getResolvedAt();
        assertThatThrownBy(()->d.refreshFrom(failed())).isInstanceOf(IllegalStateException.class).hasMessageContaining("compacted");
        assertThatThrownBy(d::requireReplayContent).isInstanceOf(IllegalStateException.class);
        d.markResolved();assertThat(d.getResolvedAt()).isEqualTo(resolved);assertThat(d.getMessage()).isNull();assertThat(d.getStatus()).isEqualTo(DeadLetterStatus.RESOLVED);
    }
    @Test void resolutionIsIdempotentBeforeCompactionAndFullEntriesStillReopen() {
        var d=new NotificationDeadLetterEntry(failed());d.markResolved();Instant resolved=d.getResolvedAt();d.markResolved();
        assertThat(d.getResolvedAt()).isEqualTo(resolved);d.refreshFrom(failed());
        assertThat(d.getStatus()).isEqualTo(DeadLetterStatus.OPEN);assertThat(d.getResolvedAt()).isNull();assertThat(d.getMessage()).isEqualTo("Fixture body");
    }
}
