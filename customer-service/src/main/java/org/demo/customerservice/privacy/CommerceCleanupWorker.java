package org.demo.customerservice.privacy;

import org.demo.platformsecurity.RequestBudget;
import org.demo.platformsecurity.privacy.CommerceCleanupAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="privacy.commerce-cleanup.enabled",havingValue="true",matchIfMissing=false)
public class CommerceCleanupWorker {
    private final PrivacyRequestStore store;private final PrivacyFenceClient client;
    public CommerceCleanupWorker(PrivacyRequestStore store,PrivacyFenceClient client){this.store=store;this.client=client;}
    @Scheduled(initialDelayString="${privacy.commerce-cleanup.initial-delay-ms:10000}",fixedDelayString="${privacy.commerce-cleanup.interval-ms:5000}")
    public void tick(){for(int n=0;n<4;n++)if(!step())break;}
    public boolean step(){var work=store.claimCommerceCleanup();if(work==null)return false;CommerceCleanupAdapter.Batch result=null;
        try(var budget=RequestBudget.open(5000)){result=client.commerceCleanup(work);}catch(RuntimeException failure){/* Only safe constants reach status. */}
        store.finishCommerceCleanup(work,result);return true;
    }
}
