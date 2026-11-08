package org.demo.customerservice.privacy;

import org.demo.platformsecurity.*;
import org.demo.platformsecurity.privacy.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @ConditionalOnProperty(name="privacy.finance-cleanup.enabled",havingValue="true",matchIfMissing=false)
public class FinanceCleanupWorker {
    private final PrivacyRequestStore store;private final FinanceHttp order,notification;
    public FinanceCleanupWorker(PrivacyRequestStore store,ServiceAuthenticationInterceptor auth,@Value("${ORDER_SERVICE_URL:http://localhost:18082}") String orderOrigin,@Value("${NOTIFICATION_SERVICE_URL:http://localhost:18086}") String notificationOrigin){this.store=store;order=new FinanceHttp(orderOrigin,auth);notification=new FinanceHttp(notificationOrigin,auth);}
    @Scheduled(initialDelayString="${privacy.finance-cleanup.initial-delay-ms:10000}",fixedDelayString="${privacy.finance-cleanup.interval-ms:5000}")
    public void tick(){for(int n=0;n<4;n++)if(!step())break;}
    private void scope(tools.jackson.databind.JsonNode data,PrivacyRequestStore.FinanceWork work){if(data.path("schemaVersion").asInt()!=1||!data.path("customerId").asText().equals(Long.toString(work.customer()))||!data.path("requestId").asText().equals(work.request().toString()))throw new IllegalStateException("FINANCE_PEER_FORMAT");}
    public boolean step(){var work=store.claimFinance();if(work==null)return false;PrivacyRequestStore.FinanceResult result=null;
        try(var budget=RequestBudget.open(5000)){
            String proof=store.journalHeader(work.request());
            var consent=order.post("/internal/privacy/finance-consent/"+work.customer(),Map.of("requestId",work.request(),"policyHash",FinancePolicy.hash(),"retryGeneration",work.generation()),proof);scope(consent,work);
            var account=notification.post("/internal/privacy/finance-account/"+work.customer(),Map.of("requestId",work.request()),proof);scope(account,work);
            var progress=order.post("/internal/privacy/finance-progress/"+work.customer(),Map.of("requestId",work.request()));scope(progress,work);
            String state=account.path("state").asText(),code=account.path("code").asText();
            if(!Set.of("MORE","WAIT","PREPARED").contains(state))throw new IllegalStateException("FINANCE_PEER_FORMAT");
            if(state.equals("PREPARED")){state=progress.path("state").asText();code=progress.path("code").asText();}
            result=new PrivacyRequestStore.FinanceResult(state,code,Long.parseLong(progress.path("orders").asText()),Long.parseLong(progress.path("preparedOrders").asText()));
        }catch(RuntimeException failure){/* Safe constants only; never retain response bodies or exception text. */}
        store.finishFinance(work,result);return true;
    }
}
