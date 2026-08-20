package org.demo.notificationservice.notification;

import org.demo.notificationservice.observability.CorrelationIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "notification.retry.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryScheduler.class);

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    public NotificationRetryScheduler(
            NotificationRepository notificationRepository,
            NotificationService notificationService
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${notification.retry.fixed-delay-ms:1000}")
    public void retryDueNotifications() {
        notificationRepository
                .findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        NotificationStatus.FAILED,
                        Instant.now()
                )
                .forEach(notification -> retryOne(notification));
    }

    private void retryOne(Notification notification) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(
                CorrelationIdContext.MDC_KEY,
                CorrelationIdContext.resolve(notification.getCorrelationId())
        )) {
            try {
                notificationService.retry(notification);
            } catch (RuntimeException exception) {
                log.warn("Notification {} retry failed: {}", notification.getId(), exception.getMessage());
            }
        }
    }
}
