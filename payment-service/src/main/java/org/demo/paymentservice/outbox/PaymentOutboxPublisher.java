package org.demo.paymentservice.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.demo.platformsecurity.BoundedJobs;

@Component
@ConditionalOnProperty(name="outbox.publisher.enabled",havingValue="true",matchIfMissing=true)
public class PaymentOutboxPublisher {
    private final PaymentOutboxStateStore states;
    private final PaymentEventPublisher publisher;
    private final BoundedJobs jobs=new BoundedJobs("payment-outbox",4,50,5000);
    public PaymentOutboxPublisher(PaymentOutboxStateStore states,PaymentEventPublisher publisher) { this.states=states;this.publisher=publisher; }
    @jakarta.annotation.PreDestroy void close() { jobs.close(); }
    @Scheduled(fixedDelayString="${outbox.publisher.fixed-delay-ms:5000}")
    public void publishPendingEvents() { states.due().forEach(id -> jobs.submit(id,() -> {
        try { publishOne(id); } catch(RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Outbox retry failed for {}",id,error); }
    })); }
    public void publishOne(Long id) {
        var ticket=states.claim(id);if(ticket==null) return;
        boolean published;
        try { published=publisher.publish(ticket.event()); } catch(RuntimeException error) { published=false; }
        states.complete(ticket,published);
    }
}
