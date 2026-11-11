package org.demo.notificationservice.notification;

import org.demo.platformobservability.CorrelationIdContext;
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

    private final org.demo.platformsecurity.BoundedJobs jobs=new org.demo.platformsecurity.BoundedJobs("notification-retry",4,20,8000);
    @jakarta.annotation.PreDestroy void closeJobs() { jobs.close(); }

    @Scheduled(fixedDelayString = "${notification.retry.fixed-delay-ms:1000}")
    public void retryDueNotifications() {
        notificationRepository.due(Instant.now(),org.springframework.data.domain.PageRequest.of(0,20)).forEach(id -> jobs.submit(id,() -> {
            try { notificationService.retry(id); }
            catch (RuntimeException failure) { log.warn("Notification {} retry failed",id,failure); }
        }));
    }
}
