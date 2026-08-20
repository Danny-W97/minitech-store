package org.demo.notificationservice.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics implements MeterBinder {

    private final NotificationDeadLetterRepository deadLetterRepository;
    private final NotificationRepository notificationRepository;

    public NotificationMetrics(
            NotificationDeadLetterRepository deadLetterRepository,
            NotificationRepository notificationRepository
    ) {
        this.deadLetterRepository = deadLetterRepository;
        this.notificationRepository = notificationRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("notification_dlq_open", deadLetterRepository,
                        repository -> repository.countByStatus(DeadLetterStatus.OPEN))
                .description("Number of open notification dead-letter entries")
                .register(registry);
        Gauge.builder("notification_retry_pending", notificationRepository,
                        repository -> repository.countByStatusAndNextAttemptAtIsNotNull(NotificationStatus.FAILED))
                .description("Number of failed notifications scheduled for retry")
                .register(registry);
    }
}
