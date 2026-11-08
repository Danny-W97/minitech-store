package org.demo.customerservice.privacy;

import org.demo.platformsecurity.RequestBudget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="customer.privacy.worker.enabled",havingValue="true",matchIfMissing=true)
public class PrivacyWorker {
    private final PrivacyRequestStore store;private final PrivacyFenceClient client;
    public PrivacyWorker(PrivacyRequestStore store,PrivacyFenceClient client){this.store=store;this.client=client;}
    @Scheduled(initialDelayString="${customer.privacy.worker.initial-delay-ms:10000}",fixedDelayString="${customer.privacy.worker.interval-ms:5000}")
    public void tick(){store.cleanup();for(int n=0;n<4;n++)if(!step())break;}
    public boolean step(){var work=store.claim();if(work==null)return false;boolean success=false;
        try(var budget=RequestBudget.open(5000)){client.fence(work);success=true;}catch(RuntimeException failure){/* Sanitized result only. No token, response, URL or exception message is retained. */}
        store.finish(work,success,"FENCE_UNAVAILABLE");return true;
    }
}
