package org.demo.notificationservice.notification;

import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.platformobservability.CorrelationIdContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationStateStore {
    @org.springframework.beans.factory.annotation.Autowired private org.demo.notificationservice.privacy.NotificationPrivacyGuard privacy;
    public record Ticket(Notification notification,String token) {}
    private final NotificationRepository notifications;
    private final NotificationRetryPolicy retry;
    private final NotificationDeadLetterService dead;
    private final long leaseMillis;
    public NotificationStateStore(NotificationRepository notifications,NotificationRetryPolicy retry,NotificationDeadLetterService dead,
            @Value("${notification.processing-lease-ms:120000}") long leaseMillis) {
        if (leaseMillis<60000) throw new IllegalArgumentException("Notification lease must cover the delivery budget");
        this.notifications=notifications; this.retry=retry; this.dead=dead; this.leaseMillis=leaseMillis;
    }
    @Transactional
    public Ticket create(CreateNotificationRequest request) {
        if(privacy!=null){var id=privacy.existing(request);if(id!=null)return new Ticket(notifications.lockId(id).orElseThrow(),null);}
        var notification=notifications.lockKey(request.eventType(),request.sourceId(),request.recipient()).orElse(null);
        if(notification!=null&&request.customerId()!=null){
            if(notification.getSubjectCustomerId()!=null&&!notification.getSubjectCustomerId().equals(request.customerId()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Notification account subject changed");
            if(request.eventType()==NotificationEventType.PASSWORD_RESET_REQUESTED&&notification.getSubjectCustomerId()==null&&notification.getStatus()!=NotificationStatus.SENT)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Legacy reset notification requires manual review");
        }
        if (notification!=null && (request.eventType().chatEvent() || notification.getStatus()==NotificationStatus.SENT || notification.processing())) return new Ticket(notification,null);
        String correlation=CorrelationIdContext.currentOrNew();
        if (notification==null) notification=new Notification(request.eventType(),request.sourceId(),request.recipient(),request.channel(),request.subject(),request.message(),correlation,request.invoiceId());
        else notification.prepareForRetry(request.recipient(),request.channel(),request.subject(),request.message(),correlation,request.invoiceId());
        if(request.customerId()!=null)notification.accountSubject(request.customerId());
        return claim(notification);
    }
    @Transactional
    public Ticket retry(long id) {
        var notification=notifications.lockId(id).orElseThrow(() -> new NotificationNotFoundException(id));
        if (privacy!=null&&privacy.sealed(notification.getEventType(),notification.getSourceId(),notification.getSubjectCustomerId()) || notification.processing() || notification.getStatus()==NotificationStatus.SENT) return new Ticket(notification,null);
        if (notification.getStatus()==NotificationStatus.FAILED && (notification.getNextAttemptAt()==null || notification.getNextAttemptAt().isAfter(Instant.now()))) return new Ticket(notification,null);
        if(notification.getStatus()==NotificationStatus.PENDING && notification.getAttemptCount()>0) {
            notification.markFailed("Delivery outcome unknown after an interrupted attempt; manual replay required",null);
            notification.release();notifications.save(notification);dead.record(notification);
            return new Ticket(notification,null);
        }
        return claim(notification);
    }
    @Transactional
    public Ticket replay(long id) {
        var entry=dead.get(id);
        long notificationId=entry.getNotificationId();
        var notification=notifications.lockId(notificationId).orElseThrow(() -> new NotificationNotFoundException(notificationId));
        if (privacy!=null&&privacy.sealed(notification.getEventType(),notification.getSourceId(),notification.getSubjectCustomerId()) || notification.processing()) return new Ticket(notification,null);
        if (notification.getStatus()==NotificationStatus.SENT) { dead.resolve(entry); return new Ticket(notification,null); }
        entry=dead.lock(id);
        if (entry.isContentCompacted()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,"Dead-letter content was removed by retention; replay is unavailable");
        notification.prepareForRetry(entry.getRecipient(),entry.getChannel(),entry.getSubject(),entry.getMessage());
        return claim(notification);
    }
    private Ticket claim(Notification notification) {
        String token=UUID.randomUUID().toString(); notification.startDeliveryAttempt();
        notification.claim(token,Instant.now().plusMillis(leaseMillis));
        notification=notifications.save(notification); notifications.flush(); return new Ticket(notification,token);
    }
    @Transactional
    public Notification complete(Ticket ticket,NotificationDeliveryResult result,boolean simulated) {
        var notification=notifications.lockId(ticket.notification().getId()).orElseThrow();
        if (!ticket.token().equals(notification.getProcessingToken())) return notification;
        if (result.sent()) notification.markSent(result.providerReference());
        else notification.markFailed(result.failureReason(),simulated || result.uncertain() ? null : retry.nextAttemptAt(notification.getAttemptCount()));
        notification.release(); notifications.save(notification);
        if (result.sent()) dead.resolveByNotificationId(notification.getId());
        else if (!simulated && notification.getNextAttemptAt()==null) dead.record(notification);
        return notification;
    }
}
