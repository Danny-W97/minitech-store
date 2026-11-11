package org.demo.billingservice.messaging;

import org.demo.platformevents.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="messaging.consumer.enabled",havingValue="true",matchIfMissing=true)
public class PaymentEventConsumer {
    private final RedisEventStreams stream;
    private final PaymentEventInbox inbox;
    private final RejectedEventStore rejected;
    private final String streamName;
    public PaymentEventConsumer(StringRedisTemplate redis,PaymentEventInbox inbox,RejectedEventStore rejected,
            @Value("${messaging.payment-stream:payment-events}") String streamName,
            @Value("${messaging.consumer.group:billing-service}") String group,
            @Value("${messaging.consumer.name:billing-service}") String name,
            @Value("${messaging.consumer.claim-idle-ms:60000}") long idle,
            @Value("${messaging.retention.age-ms:604800000}") long retention,
            @Value("${messaging.retention.required-groups:notification-service,billing-service}") String requiredGroups) {
        this.streamName=streamName;this.inbox=inbox;this.rejected=rejected;
        stream=new RedisEventStreams(redis,streamName,group,name,idle,retention,requiredGroups);
    }
    @Scheduled(fixedDelayString="${messaging.consumer.fixed-delay-ms:1000}")
    public void consume() {
        try { stream.poll(this::processRecord); }
        catch(RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment stream polling failed; records remain pending",error); }
    }
    void processRecord(MapRecord<String,?,?> record) {
        var fields=EventIdentity.fields(record.getValue());
        if("true".equals(fields.get("_bootstrap"))) { stream.acknowledge(record.getId());return; }
        try {
            try {
                PaymentEventData.parse(fields);
                String id=EventIdentity.id(streamName,record.getId().getValue(),fields);
                try { inbox.accept(id,fields); }
                catch(org.springframework.dao.DataIntegrityViolationException insertRace) { inbox.accept(id,fields); }
            } catch(IllegalArgumentException malformed) {
                try { rejected.reject(streamName,record.getId().getValue(),fields,malformed.getMessage()); }
                catch(org.springframework.dao.DataIntegrityViolationException insertRace) { rejected.reject(streamName,record.getId().getValue(),fields,malformed.getMessage()); }
            }
            stream.acknowledge(record.getId());
        } catch(RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Event {} remains pending until its SQL commit/ACK succeeds",record.getId(),error); }
    }
}
