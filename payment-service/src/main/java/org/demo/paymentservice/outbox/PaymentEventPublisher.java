package org.demo.paymentservice.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PaymentEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final String streamName;

    public PaymentEventPublisher(
            StringRedisTemplate redisTemplate,
            @Value("${messaging.payment-stream}") String streamName
    ) {
        this.redisTemplate = redisTemplate;
        this.streamName = streamName;
    }

    public boolean publish(PaymentOutboxEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventKey", event.getEventKey());
        fields.put("eventType", event.getEventType());
        fields.put("orderId", event.getOrderId().toString());
        fields.put("amount", event.getAmount().toPlainString());
        fields.put("recipient", event.getRecipient());
        if (event.getDeliveryAddress() != null) {
            fields.put("deliveryAddress", event.getDeliveryAddress());
        }
        if (event.getCorrelationId() != null) {
            fields.put("correlationId", event.getCorrelationId());
        }
        if (event.getInvoiceId() != null) {
            fields.put("invoiceId", event.getInvoiceId().toString());
        }

        try {
            return redisTemplate.opsForStream()
                    .add(StreamRecords.newRecord()
                            .ofMap(fields)
                            .withStreamKey(streamName)) != null;
        } catch (DataAccessException | IllegalStateException exception) {
            return false;
        }
    }
}
