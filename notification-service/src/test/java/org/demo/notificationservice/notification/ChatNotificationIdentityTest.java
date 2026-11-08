package org.demo.notificationservice.notification;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ChatNotificationIdentityTest {
    @Test void aChatRetryDoesNotBypassAnUncertainOrTerminalDelivery(){
        var repository=mock(NotificationRepository.class);
        var states=new NotificationStateStore(repository,mock(NotificationRetryPolicy.class),mock(NotificationDeadLetterService.class),120000);
        var notification=new Notification(NotificationEventType.CHAT_ENTRY_REPLIED,1L,"guest@example.test",NotificationChannel.EMAIL,"Subject","Body","correlation",null);
        notification.markFailed("Uncertain delivery",null);
        when(repository.lockKey(any(),any(),any())).thenReturn(Optional.of(notification));
        var request=new CreateNotificationRequest(NotificationEventType.CHAT_ENTRY_REPLIED,1L,"guest@example.test",NotificationChannel.EMAIL,"Subject","Body",false);
        assertThat(states.create(request).token()).isNull();verify(repository,never()).save(any());
        for(var type:java.util.List.of(NotificationEventType.CHAT_MESSAGE_RECEIVED,NotificationEventType.CHAT_REPLY_SENT,NotificationEventType.CHAT_ENTRY_RECEIVED,NotificationEventType.CHAT_ENTRY_REPLIED))
            assertThat(type.chatEvent()).isTrue();
    }
}
