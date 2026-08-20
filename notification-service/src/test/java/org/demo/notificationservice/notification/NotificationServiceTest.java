package org.demo.notificationservice.notification;

import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.notificationservice.web.NotificationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationDeliveryService deliveryService;

    @Mock
    private NotificationRetryPolicy retryPolicy;

    @Mock
    private NotificationDeadLetterService deadLetterService;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void notificationIsSentAndStored() {
        when(notificationRepository.findByEventTypeAndSourceIdAndRecipient(
                NotificationEventType.PAYMENT_SUCCEEDED, 11L, "customer@example.com"
        )).thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryService.deliver(any(Notification.class), eq(false)))
                .thenReturn(NotificationDeliveryResult.sent("SMTP-12345678"));

        NotificationResponse response = notificationService.create(new CreateNotificationRequest(
                NotificationEventType.PAYMENT_SUCCEEDED,
                11L,
                "customer@example.com",
                NotificationChannel.EMAIL,
                "Payment confirmed",
                "Payment for order 11 was confirmed.",
                false
        ));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(response.providerReference()).isEqualTo("SMTP-12345678");
        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    @Test
    void simulatedFailureCanBeRetried() {
        when(notificationRepository.findByEventTypeAndSourceIdAndRecipient(
                NotificationEventType.PAYMENT_FAILED, 11L, "customer@example.com"
        )).thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryService.deliver(any(Notification.class), eq(true)))
                .thenReturn(NotificationDeliveryResult.failed(
                        "Notification delivery declined by the simulation"
                ));

        NotificationResponse response = notificationService.create(new CreateNotificationRequest(
                NotificationEventType.PAYMENT_FAILED,
                11L,
                "customer@example.com",
                NotificationChannel.EMAIL,
                "Payment failed",
                "Payment for order 11 failed.",
                true
        ));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.failureReason()).isEqualTo("Notification delivery declined by the simulation");
    }

    @Test
    void providerFailureSchedulesBoundedRetry() {
        Instant nextAttemptAt = Instant.now().plusSeconds(5);
        when(notificationRepository.findByEventTypeAndSourceIdAndRecipient(
                NotificationEventType.PAYMENT_FAILED, 11L, "customer@example.com"
        )).thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryService.deliver(any(Notification.class), eq(false)))
                .thenReturn(NotificationDeliveryResult.failed("SMTP unavailable"));
        when(retryPolicy.nextAttemptAt(1)).thenReturn(nextAttemptAt);

        NotificationResponse response = notificationService.create(new CreateNotificationRequest(
                NotificationEventType.PAYMENT_FAILED,
                11L,
                "customer@example.com",
                NotificationChannel.EMAIL,
                "Payment failed",
                "Payment for order 11 failed.",
                false
        ));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        var savedNotifications = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(savedNotifications.capture());
        Notification stored = savedNotifications.getAllValues().get(1);
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(stored.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    }

    @Test
    void sentNotificationIsIdempotentForTheSameEvent() {
        Notification existingNotification = new Notification(
                NotificationEventType.PAYMENT_SUCCEEDED,
                11L,
                "customer@example.com",
                NotificationChannel.EMAIL,
                "Payment confirmed",
                "Payment for order 11 was confirmed."
        );
        existingNotification.markSent("SIM-12345678");
        when(notificationRepository.findByEventTypeAndSourceIdAndRecipient(
                NotificationEventType.PAYMENT_SUCCEEDED, 11L, "customer@example.com"
        )).thenReturn(Optional.of(existingNotification));

        NotificationResponse response = notificationService.create(new CreateNotificationRequest(
                NotificationEventType.PAYMENT_SUCCEEDED,
                11L,
                "customer@example.com",
                NotificationChannel.EMAIL,
                "Payment confirmed",
                "Payment for order 11 was confirmed.",
                false
        ));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(response.providerReference()).isEqualTo("SIM-12345678");
        verify(notificationRepository, never()).save(any(Notification.class));
    }
}
