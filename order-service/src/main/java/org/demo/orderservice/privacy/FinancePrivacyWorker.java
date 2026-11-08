package org.demo.orderservice.privacy;

import org.demo.platformsecurity.*;
import org.demo.platformsecurity.privacy.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @ConditionalOnProperty(name="privacy.finance-cleanup.enabled",havingValue="true",matchIfMissing=false)
public class FinancePrivacyWorker {
    private final FinancePrivacyJobs jobs;private final Map<String,FinanceHttp> clients=new HashMap<>();
    public FinancePrivacyWorker(FinancePrivacyJobs jobs,Environment env,ServiceAuthenticationInterceptor auth){
        this.jobs=jobs;String[] names={"returns","payment","inventory","promotion","delivery","billing","notification"};int[] ports={18094,18084,18083,18089,18088,18095,18086};
        for(int n=0;n<names.length;n++)clients.put(names[n]+"-service",new FinanceHttp(env.getProperty(names[n].toUpperCase(Locale.ROOT)+"_SERVICE_URL","http://localhost:"+ports[n]),auth));
    }
    @Scheduled(initialDelayString="${privacy.finance-cleanup.initial-delay-ms:10000}",fixedDelayString="${privacy.finance-cleanup.interval-ms:5000}")
    public void tick(){jobs.recover();for(int n=0;n<4;n++)if(!step())break;}
    public boolean step(){var work=jobs.claim();if(work==null)return false;
        if(work.step()==7){jobs.seal(work);return true;}FinancePreparationAdapter.Result result=null;
        try(var budget=RequestBudget.open(5000)){
            String service=FinancePrivacyJobs.PEERS.get(work.step());var data=clients.get(service).post("/internal/privacy/finance-prepare/"+work.customer(),Map.of("requestId",work.request(),"orderId",work.order(),"orderVersion",work.version(),"cursor",work.cursor()),jobs.journalHeader(work));
            FinanceHttp.scope(data,work.customer(),work.request(),work.order(),work.version());if(!data.path("service").asText().equals(service))throw new IllegalStateException("FINANCE_PEER_FORMAT");
            result=new FinancePreparationAdapter.Result(data.path("state").asText(),data.path("code").asText(),data.path("cursor").asText());
        }catch(RuntimeException failure){/* Do not store remote text, URLs or contact data. */}
        jobs.finish(work,result);return true;
    }
}
