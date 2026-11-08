package org.demo.notificationservice.notification;

import org.demo.platformobservability.CorrelationIdContext;
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
    private final NotificationStateStore states;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationDeliveryService deliveryService,
            NotificationRetryPolicy retryPolicy,
            NotificationDeadLetterService deadLetterService, NotificationStateStore states
    ) {
        this.notificationRepository = notificationRepository;
        this.deliveryService = deliveryService;
        this.retryPolicy = retryPolicy;
        this.deadLetterService = deadLetterService;
        this.states = states;
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public NotificationResponse create(CreateNotificationRequest request) {
        NotificationStateStore.Ticket ticket;
        try { ticket=states.create(request); }
        catch (org.springframework.dao.DataIntegrityViolationException concurrentInsert) { ticket=states.create(request); }
        return deliver(ticket,request.simulateFailure());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void retry(Notification notification) { retry(notification.getId()); }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void retry(Long id) { deliver(states.retry(id),false); }

    private NotificationResponse deliver(NotificationStateStore.Ticket ticket,boolean simulated) {
        if (ticket.token()==null) return NotificationResponse.from(ticket.notification());
        NotificationDeliveryResult result;
        try (var ignored=org.demo.platformsecurity.RequestBudget.open(8000);
             var correlation=CorrelationIdContext.open(ticket.notification().getCorrelationId())) {
            org.demo.platformsecurity.RequestBudget.check();
            result=deliveryService.deliver(ticket.notification(),simulated);
        } catch (RuntimeException failure) { result=NotificationDeliveryResult.uncertain("Delivery outcome unknown; manual review required: "+failure.getMessage()); }
        return NotificationResponse.from(states.complete(ticket,result,simulated));
    }

    public NotificationResponse get(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .map(NotificationResponse::from)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }

    public List<NotificationResponse> getAll() {
        return list(null, null, org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    public List<NotificationResponse> getBySourceId(Long sourceId) {
        return list(sourceId, null, org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<NotificationResponse> list(Long sourceId, NotificationStatus status,
            org.demo.platformsecurity.ListWindow window) {
        return window.map(notificationRepository.page(window.ceiling(), sourceId, status, window.fetch()), Notification::getId, NotificationResponse::from);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public NotificationResponse replayDeadLetter(Long id) { return deliver(states.replay(id),false); }
}
