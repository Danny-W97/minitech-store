package org.demo.customerservice.privacy;

import org.demo.platformsecurity.RequestBudget;
import org.demo.platformsecurity.privacy.AccountCleanupAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="privacy.account-cleanup.enabled",havingValue="true",matchIfMissing=false)
public class AccountCleanupWorker {
    private final PrivacyRequestStore store;private final PrivacyFenceClient client;private final CustomerAccountCleanup local;
    public AccountCleanupWorker(PrivacyRequestStore store,PrivacyFenceClient client,CustomerAccountCleanup local){this.store=store;this.client=client;this.local=local;}
    @Scheduled(initialDelayString="${privacy.account-cleanup.initial-delay-ms:10000}",fixedDelayString="${privacy.account-cleanup.interval-ms:5000}")
    public void tick(){for(int n=0;n<4;n++)if(!step())break;}
    public boolean step(){var work=store.claimAccountCleanup();if(work==null)return false;AccountCleanupAdapter.Result result=null;
        try(var budget=RequestBudget.open(5000)){store.journalHeader(work.request());result=work.service().equals("customer-service")?local.cleanup(work.customer(),work.request()):client.accountCleanup(work);}catch(RuntimeException failure){/* Store only a safe constant code. */}
        store.finishAccountCleanup(work,result);return true;
    }
}
