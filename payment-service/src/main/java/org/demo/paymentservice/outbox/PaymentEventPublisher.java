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
    private final PaymentIdentityReadiness readiness;

    public PaymentEventPublisher(StringRedisTemplate redisTemplate,String streamName){this(redisTemplate,streamName,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentEventPublisher(
            StringRedisTemplate redisTemplate,
            @Value("${messaging.payment-stream}") String streamName,PaymentIdentityReadiness readiness
    ) {
        this.redisTemplate = redisTemplate;
        this.streamName = streamName;
        this.readiness=readiness;
    }

    public boolean publish(PaymentOutboxEvent event) {
        event.requirePublishablePayload();
        if(event.getIdentityVersion()==2&&(readiness==null||!readiness.ready()))return false;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", event.getEventId());
        if(event.getIdentityVersion()==2){fields.put("protocolVersion","2");fields.put("legacyEventId",event.getLegacyEventId());}
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
            return org.demo.platformevents.RedisPrivacyStreams.publish(redisTemplate,streamName,event.getOrderId(),fields);
        } catch (DataAccessException | IllegalStateException exception) {
            return false;
        }
    }
}
