package org.demo.notificationservice.messaging;

import org.demo.notificationservice.notification.NotificationChannel;
import org.demo.notificationservice.notification.NotificationEventType;
import org.demo.notificationservice.notification.NotificationService;
import org.demo.notificationservice.notification.NotificationStatus;
import org.demo.platformobservability.CorrelationIdContext;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.notificationservice.web.NotificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "messaging.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final org.demo.platformevents.RedisEventStreams stream;
    private final NotificationEventInbox inbox;
    private final RejectedEventStore rejected;
    private final String streamName;
    public PaymentEventConsumer(StringRedisTemplate redis,NotificationEventInbox inbox,RejectedEventStore rejected,
            @Value("${messaging.payment-stream}") String streamName,
            @Value("${messaging.consumer.group:notification-service}") String group,
            @Value("${messaging.consumer.name:notification-service}") String name,
            @Value("${messaging.consumer.claim-idle-ms:60000}") long idle,
            @Value("${messaging.retention.age-ms:604800000}") long retention,
            @Value("${messaging.retention.required-groups:notification-service,billing-service}") String requiredGroups) {
        this.streamName=streamName;this.inbox=inbox;this.rejected=rejected;
        stream=new org.demo.platformevents.RedisEventStreams(redis,streamName,group,name,idle,retention,requiredGroups);
    }
    @Scheduled(fixedDelayString="${messaging.consumer.fixed-delay-ms:1000}")
    public void consume() {
        try { stream.poll(this::processRecord); }
        catch(RuntimeException error) { log.warn("Payment stream polling failed; records remain pending",error); }
    }
    void processRecord(MapRecord<String,?,?> record) {
        var fields=org.demo.platformevents.EventIdentity.fields(record.getValue());
        if("true".equals(fields.get("_bootstrap"))) { stream.acknowledge(record.getId());return; }
        try(var correlation=CorrelationIdContext.open(fields.get("correlationId"))) {
            try {
                org.demo.platformevents.PaymentEventData.parse(fields);
                String id=org.demo.platformevents.EventIdentity.id(streamName,record.getId().getValue(),fields);
                var request=toNotificationRequest(record);
                if(request.message().length()>1000) throw new IllegalArgumentException("Notification message exceeds the schema limit");
                try { inbox.accept(id,fields,request); }
                catch(org.springframework.dao.DataIntegrityViolationException insertRace) { inbox.accept(id,fields,request); }
            } catch(IllegalArgumentException malformed) {
                try { rejected.reject(streamName,record.getId().getValue(),fields,malformed.getMessage()); }
                catch(org.springframework.dao.DataIntegrityViolationException insertRace) { rejected.reject(streamName,record.getId().getValue(),fields,malformed.getMessage()); }
            }
            stream.acknowledge(record.getId());
        } catch(RuntimeException error) { log.warn("Event {} remains pending until its SQL commit/ACK succeeds",record.getId(),error); }
    }

    private CreateNotificationRequest toNotificationRequest(MapRecord<String, ?, ?> record) {
        Map<?, ?> fields = record.getValue();
        NotificationEventType eventType = NotificationEventType.valueOf(required(fields, "eventType"));
        Long orderId = Long.valueOf(required(fields, "orderId"));
        BigDecimal amount = new BigDecimal(required(fields, "amount"));
        String recipient = required(fields, "recipient");
        String deliveryAddress = optional(fields, "deliveryAddress");
        boolean succeeded = eventType == NotificationEventType.PAYMENT_SUCCEEDED;
        boolean pending = eventType == NotificationEventType.PAYMENT_PENDING;
        String invoiceId = optional(fields, "invoiceId");

        return new CreateNotificationRequest(
                eventType,
                orderId,
                recipient,
                NotificationChannel.EMAIL,
                pending ? "Payment pending" : succeeded ? "Payment confirmed" : "Payment failed",
                message(orderId, amount, succeeded, pending, deliveryAddress, invoiceId != null),
                false,
                invoiceId == null ? null : Long.valueOf(invoiceId)
        );
    }

    private String message(Long orderId, BigDecimal amount, boolean succeeded, boolean pending, String deliveryAddress,
                           boolean invoiceAttached) {
        String message = pending
                ? "Order %d was accepted. Payment will be collected on delivery or pickup. Amount: %s PLN."
                .formatted(orderId, amount)
                : succeeded
                ? "Payment for order %d was confirmed. Amount: %s PLN."
                .formatted(orderId, amount)
                : "Payment for order %d failed."
                .formatted(orderId);
        return deliveryAddress == null || deliveryAddress.isBlank()
                ? withInvoiceNote(message, invoiceAttached)
                : withInvoiceNote(message + " Delivery address: " + deliveryAddress + ".", invoiceAttached);
    }

    private String withInvoiceNote(String message, boolean invoiceAttached) {
        return invoiceAttached ? message + " Invoice PDF is attached." : message;
    }

    private String required(Map<?, ?> fields, String name) {
        Object rawValue = fields.get(name);
        String value = rawValue == null ? null : rawValue.toString();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing event field: " + name);
        }
        return value;
    }

    private String optional(Map<?, ?> fields, String name) {
        Object rawValue = fields.get(name);
        return rawValue == null ? null : rawValue.toString();
    }

}
