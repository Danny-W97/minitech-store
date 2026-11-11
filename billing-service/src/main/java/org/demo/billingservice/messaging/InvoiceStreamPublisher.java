package org.demo.billingservice.messaging;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
@Component
public class InvoiceStreamPublisher {
    private final StringRedisTemplate redis;
    private final String stream;
    public InvoiceStreamPublisher(StringRedisTemplate redis,@Value("${messaging.invoice-stream:invoice-events}") String stream) { this.redis=redis;this.stream=stream; }
    public boolean publish(InvoiceOutboxEvent event) { var fields=event.fields();return org.demo.platformevents.RedisPrivacyStreams.publish(redis,stream,Long.parseLong(fields.get("orderId")),fields); }
}
