package org.demo.notificationservice.notification;

import org.demo.notificationservice.observability.CorrelationIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.notificationservice.web.NotificationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryService deliveryService;
    private final NotificationRetryPolicy retryPolicy;
    private final NotificationDeadLetterService deadLetterService;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationDeliveryService deliveryService,
            NotificationRetryPolicy retryPolicy,
            NotificationDeadLetterService deadLetterService
    ) {
        this.notificationRepository = notificationRepository;
        this.deliveryService = deliveryService;
        this.retryPolicy = retryPolicy;
        this.deadLetterService = deadLetterService;
    }

    @Transactional
    public NotificationResponse create(CreateNotificationRequest request) {
        String correlationId = CorrelationIdContext.currentOrNew();
        log.info("Creating notification for event {} and source {}", request.eventType(), request.sourceId());
        Notification existing = notificationRepository
                .findByEventTypeAndSourceIdAndRecipient(
                        request.eventType(), request.sourceId(), request.recipient()
                )
                .orElse(null);

        if (existing != null && existing.getStatus() == NotificationStatus.SENT) {
            return NotificationResponse.from(existing);
        }

        Notification notification = existing;
        if (notification == null) {
            notification = new Notification(
                    request.eventType(),
                    request.sourceId(),
                    request.recipient(),
                    request.channel(),
                    request.subject(),
                    request.message(),
                    correlationId,
                    request.invoiceId()
            );
        } else {
            notification.prepareForRetry(
                    request.recipient(),
                    request.channel(),
                    request.subject(),
                    request.message(),
                    correlationId,
                    request.invoiceId()
            );
        }
        return NotificationResponse.from(deliverAndStore(notification, request.simulateFailure()));
    }

    @Transactional
    public void retry(Notification notification) {
        if (notification.getStatus() != NotificationStatus.FAILED
                || notification.getNextAttemptAt() == null) {
            return;
        }
        deliverAndStore(notification, false);
    }

    private Notification deliverAndStore(Notification notification, boolean simulateFailure) {
        notification.startDeliveryAttempt();
        notificationRepository.save(notification);

        NotificationDeliveryResult delivery = deliveryService.deliver(notification, simulateFailure);
        if (delivery.sent()) {
            notification.markSent(delivery.providerReference());
        } else {
            Instant nextAttemptAt = simulateFailure
                    ? null
                    : retryPolicy.nextAttemptAt(notification.getAttemptCount());
            notification.markFailed(delivery.failureReason(), nextAttemptAt);
        }
        Notification stored = notificationRepository.save(notification);
        log.info("Notification {} delivery status: {}", stored.getId(), stored.getStatus());
        if (stored.getStatus() == NotificationStatus.SENT) {
            deadLetterService.resolveByNotificationId(stored.getId());
        } else if (!simulateFailure && stored.getNextAttemptAt() == null) {
            deadLetterService.record(stored);
        }
        return stored;
    }

    public NotificationResponse get(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .map(NotificationResponse::from)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }

    public List<NotificationResponse> getAll() {
        return notificationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(NotificationResponse::from)
                .toList();
    }

    public List<NotificationResponse> getBySourceId(Long sourceId) {
        return notificationRepository.findAllBySourceIdOrderByCreatedAtDesc(sourceId).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional
    public NotificationResponse replayDeadLetter(Long deadLetterId) {
        NotificationDeadLetterEntry deadLetter = deadLetterService.get(deadLetterId);
        Notification notification = notificationRepository.findById(deadLetter.getNotificationId())
                .orElseThrow(() -> new NotificationNotFoundException(deadLetter.getNotificationId()));

        notification.prepareForRetry(
                deadLetter.getRecipient(),
                deadLetter.getChannel(),
                deadLetter.getSubject(),
                deadLetter.getMessage()
        );
        NotificationResponse response = NotificationResponse.from(deliverAndStore(notification, false));
        if (response.status() == NotificationStatus.SENT) {
            deadLetterService.resolve(deadLetter);
        }
        return response;
    }
}
