package org.demo.notificationservice.messaging;

import org.demo.platformevents.EventIdentity;
import org.demo.notificationservice.notification.*;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.platformobservability.CorrelationIdContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class NotificationEventInbox {
    private final EventReceiptRepository receipts;
    private final NotificationRepository notifications;
    private final org.springframework.jdbc.core.JdbcTemplate aliases;
    @org.springframework.beans.factory.annotation.Autowired private org.demo.platformsecurity.privacy.PrivacySql privacy;
    public NotificationEventInbox(EventReceiptRepository receipts,NotificationRepository notifications) { this(receipts,notifications,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public NotificationEventInbox(EventReceiptRepository receipts,NotificationRepository notifications,org.springframework.jdbc.core.JdbcTemplate aliases) { this.receipts=receipts;this.notifications=notifications;this.aliases=aliases; }
    @Transactional public void accept(String id,Map<String,String> fields,CreateNotificationRequest request) {
        var identity=EventIdentity.envelope(id,fields);
        if(identity.aliasId()!=null){var oldReader=receipts.findById(identity.aliasId());if(oldReader.isPresent()&&!oldReader.get().getPayloadHash().equals(EventIdentity.fingerprint(fields)))throw new IllegalArgumentException("Event ID was reused with different content");}
        org.demo.platformevents.EventAliases.bind(aliases,identity);id=identity.canonicalId();String hash=identity.payloadHash();
        var previous=receipts.findById(id);
        if(previous.isPresent()) {
            if(!previous.get().getPayloadHash().equals(hash)) throw new IllegalArgumentException("Event ID was reused with different content");
            saveAlias(previous.get(),identity,fields);return;
        }
        var event=org.demo.platformevents.PaymentEventData.parse(fields);
        if(event.orderId()!=request.sourceId()||!event.recipient().equals(request.recipient())||!event.type().equals(request.eventType().name()))throw new IllegalArgumentException("Notification scope mismatch");
        if(privacy!=null&&privacy.orderSealed(event.orderId())){var suppressed=new EventReceipt(id,hash,"SUPPRESSED",null,null,null).forOrder(event.orderId());receipts.saveAndFlush(suppressed);saveAlias(suppressed,identity,fields);return;}
        var notification=notifications.lockKey(request.eventType(),request.sourceId(),request.recipient()).orElse(null);
        if(notification==null) {
            notification=new Notification(request.eventType(),request.sourceId(),request.recipient(),request.channel(),request.subject(),request.message(),CorrelationIdContext.currentOrNew(),request.invoiceId());
            notification.queueForDelivery();notification=notifications.saveAndFlush(notification);
        }
        var accepted=new EventReceipt(id,hash,"ACCEPTED",notification.getId(),null,null).forOrder(event.orderId());receipts.saveAndFlush(accepted);saveAlias(accepted,identity,fields);
    }

    private void saveAlias(EventReceipt canonical,EventIdentity.Envelope identity,Map<String,String> fields){
        if(identity.aliasId()!=null&&!receipts.existsById(identity.aliasId()))receipts.saveAndFlush(canonical.alias(identity.aliasId(),EventIdentity.fingerprint(fields)));
    }
}
