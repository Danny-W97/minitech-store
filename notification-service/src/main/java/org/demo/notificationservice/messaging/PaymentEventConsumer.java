package org.demo.notificationservice.messaging;

import org.demo.notificationservice.notification.NotificationChannel;
import org.demo.notificationservice.notification.NotificationEventType;
import org.demo.notificationservice.notification.NotificationService;
import org.demo.notificationservice.notification.NotificationStatus;
import org.demo.notificationservice.observability.CorrelationIdContext;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.notificationservice.web.NotificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;
    private final String streamName;
    private final String groupName;
    private final String consumerName;
    private boolean groupReady;

    public PaymentEventConsumer(
            StringRedisTemplate redisTemplate,
            NotificationService notificationService,
            @Value("${messaging.payment-stream}") String streamName,
            @Value("${messaging.consumer.group}") String groupName,
            @Value("${messaging.consumer.name}") String consumerName
    ) {
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
        this.streamName = streamName;
        this.groupName = groupName;
        this.consumerName = consumerName;
    }

    @Scheduled(fixedDelayString = "${messaging.consumer.fixed-delay-ms:1000}")
    public void consume() {
        try {
            ensureConsumerGroup();
            consumeBatch(ReadOffset.from("0-0"));
            consumeBatch(ReadOffset.lastConsumed());
        } catch (DataAccessException exception) {
            log.warn("Payment event broker is unavailable for stream '{}' and group '{}': {}",
                    streamName, groupName, exception.getMessage(), exception);
        }
    }

    void processRecord(MapRecord<String, ?, ?> record) {
        if ("true".equals(record.getValue().get("_bootstrap"))) {
            acknowledge(record);
            return;
        }

        String correlationId = CorrelationIdContext.resolve(optional(record.getValue(), "correlationId"));
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdContext.MDC_KEY, correlationId)) {
            log.info("Processing payment event {}", record.getId());
            try {
                NotificationResponse response = notificationService.create(toNotificationRequest(record));
                if (response.status() == NotificationStatus.SENT) {
                    acknowledge(record);
                } else {
                    acknowledge(record);
                    log.warn("Payment event {} acknowledged after durable notification retry scheduling", record.getId());
                }
            } catch (IllegalArgumentException exception) {
                log.warn("Ignoring malformed payment event {}: {}", record.getId(), exception.getMessage());
                acknowledge(record);
            } catch (RuntimeException exception) {
                log.warn("Payment event {} will be retried: {}", record.getId(), exception.getMessage());
            }
        }
    }

    private void ensureConsumerGroup() {
        if (groupReady) {
            return;
        }

        redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .ofMap(Map.of("_bootstrap", "true"))
                .withStreamKey(streamName));
        try {
            redisTemplate.opsForStream().createGroup(
                    streamName,
                    ReadOffset.from("0-0"),
                    groupName
            );
        } catch (DataAccessException exception) {
            if (!isConsumerGroupAlreadyExists(exception)) {
                throw exception;
            }
        }
        groupReady = true;
    }

    private boolean isConsumerGroupAlreadyExists(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (String.valueOf(current.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void consumeBatch(ReadOffset offset) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                StreamReadOptions.empty().count(20),
                StreamOffset.create(streamName, offset)
        );
        if (records != null) {
            records.forEach(this::processRecord);
        }
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

    private void acknowledge(MapRecord<String, ?, ?> record) {
        redisTemplate.opsForStream().acknowledge(streamName, groupName, record.getId());
    }
}
