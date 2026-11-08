package org.demo.paymentservice.followup;

import org.demo.platformsecurity.BoundedJobs;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="payment.follow-ups.enabled",havingValue="true",matchIfMissing=true)
public class FollowUpScheduler {
    private final FollowUpStateStore states;
    private final FollowUpRunner runner;
    private final BoundedJobs jobs=new BoundedJobs("payment-follow-up",4,50,10000);
    public FollowUpScheduler(FollowUpStateStore states,FollowUpRunner runner) { this.states=states;this.runner=runner; }
    @Scheduled(fixedDelayString="${payment.follow-ups.fixed-delay-ms:1000}")
    public void recover() {
        states.due().forEach(id -> jobs.submit(id,() -> {
            try { runner.runOne(id); }
            catch(RuntimeException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment follow-up {} awaits lease recovery",id); }
        }));
    }
    @jakarta.annotation.PreDestroy void close() { jobs.close(); }
}
