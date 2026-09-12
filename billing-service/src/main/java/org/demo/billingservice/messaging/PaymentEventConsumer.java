package org.demo.billingservice.messaging;

import org.demo.billingservice.billing.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "messaging.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final InvoiceRepository invoiceRepository;
    private final String streamName;
    private final String groupName;
    private final String consumerName;
    private boolean groupReady;

    public PaymentEventConsumer(
            StringRedisTemplate redisTemplate,
            InvoiceRepository invoiceRepository,
            @Value("${messaging.payment-stream}") String streamName,
            @Value("${messaging.consumer.group:billing-service}") String groupName,
            @Value("${messaging.consumer.name:billing-service}") String consumerName
    ) {
        this.redisTemplate = redisTemplate;
        this.invoiceRepository = invoiceRepository;
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
                    streamName, groupName, exception.getMessage());
        }
    }

    void processRecord(MapRecord<String, ?, ?> record) {
        if ("true".equals(record.getValue().get("_bootstrap"))) {
            acknowledge(record);
            return;
        }

        try {
            Map<?, ?> fields = record.getValue();
            String eventType = String.valueOf(fields.get("eventType"));
            String orderIdStr = String.valueOf(fields.get("orderId"));

            if ("PAYMENT_SUCCEEDED".equalsIgnoreCase(eventType) && orderIdStr != null && !orderIdStr.isBlank()) {
                Long orderId = Long.valueOf(orderIdStr);
                boolean exists = invoiceRepository.findByOrderId(orderId).isPresent();
                if (exists) {
                    log.debug("Invoice for order #{} already exists.", orderId);
                } else {
                    log.info("Received PaymentSucceeded event for order #{}. Verified in billing repository.", orderId);
                }
            }
            acknowledge(record);
        } catch (Exception exception) {
            log.warn("Error processing payment event {}: {}", record.getId(), exception.getMessage());
            acknowledge(record);
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

    private void acknowledge(MapRecord<String, ?, ?> record) {
        redisTemplate.opsForStream().acknowledge(streamName, groupName, record.getId());
    }
}
