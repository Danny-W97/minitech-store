package org.demo.customerservice.privacy;

import org.demo.platformsecurity.*;
import org.demo.customerservice.export.jobs.ExportJson;
import org.demo.platformsecurity.privacy.PrivacyFenceStore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;

@Component
public class PrivacyFenceClient {
    @org.springframework.beans.factory.annotation.Autowired private PrivacyRequestStore journalStore;
    private final RestClient client;private final Map<String,String> origins=new HashMap<>();private final PrivacyFenceStore local;private final ExportJson json;
    public PrivacyFenceClient(Environment env,ServiceAuthenticationInterceptor authentication,PrivacyFenceStore local,ExportJson json){
        this.local=local;this.json=json;client=PlatformSecurityConfiguration.client().requestInterceptor(authentication).build();
        String[] names={"product","order","inventory","payment","notification","delivery","promotion","wishlist","review","recommendation","chat","returns","billing"};int[] ports={18081,18082,18083,18084,18086,18088,18089,18090,18091,18092,18093,18094,18095};
        for(int n=0;n<names.length;n++){String value=env.getProperty(names[n].toUpperCase(Locale.ROOT)+"_SERVICE_URL","http://localhost:"+ports[n]);var uri=URI.create(value);
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalStateException("Invalid privacy service origin");
            origins.put(names[n]+"-service",value.replaceAll("/+$",""));}
    }
    public void fence(PrivacyRequestStore.Work work){
        if(work.service().equals("customer-service")){local.fence(work.customer(),work.request());return;}
        client.post().uri(URI.create(origins.get(work.service())+"/internal/privacy/fences/"+work.customer())).body(Map.of("requestId",work.request())).exchange((request,response)->{
            if(response.getStatusCode().value()!=200)throw new IllegalStateException("FENCE_UNAVAILABLE");
            byte[] raw=response.getBody().readNBytes(4097);if(raw.length>4096)throw new IllegalStateException("FENCE_FORMAT");var result=json.parse(raw);
            if(result.path("schemaVersion").asInt()!=1||!result.path("customerId").asText().equals(Long.toString(work.customer()))||!result.path("requestId").asText().equals(work.request().toString())||!result.path("service").asText().equals(work.service())||!result.path("state").asText().equals("BLOCKED"))throw new IllegalStateException("FENCE_FORMAT");return true;
        });
    }
    public org.demo.platformsecurity.privacy.AccountCleanupAdapter.Result accountCleanup(PrivacyRequestStore.Work work){
        if(!PrivacyPlan.ACCOUNT_SERVICES.contains(work.service())||work.service().equals("customer-service"))throw new IllegalArgumentException("Invalid cleanup scope");
        return client.post().uri(URI.create(origins.get(work.service())+"/internal/privacy/account-cleanup/"+work.customer())).header(org.demo.platformsecurity.privacy.JournalReceiptVerifier.HEADER,journalStore.journalHeader(work.request())).body(Map.of("requestId",work.request())).exchange((request,response)->{
            if(response.getStatusCode().value()!=200)throw new IllegalStateException("CLEANUP_UNAVAILABLE");
            byte[] raw=response.getBody().readNBytes(4097);if(raw.length>4096)throw new IllegalStateException("CLEANUP_FORMAT");var result=json.parse(raw);
            if(result.path("schemaVersion").asInt()!=1||!result.path("customerId").asText().equals(Long.toString(work.customer()))||!result.path("requestId").asText().equals(work.request().toString())||!result.path("service").asText().equals(work.service()))throw new IllegalStateException("CLEANUP_FORMAT");
            var outcome=org.demo.platformsecurity.privacy.AccountCleanupAdapter.Outcome.valueOf(result.path("outcome").asText());String code=result.path("code").asText();
            if(work.service().equals("chat-service")){if(outcome!=org.demo.platformsecurity.privacy.AccountCleanupAdapter.Outcome.SEPARATE_PROOF_REQUIRED||!code.equals("SEPARATE_PROOF_REQUIRED"))throw new IllegalStateException("CLEANUP_FORMAT");}
            else if(outcome==org.demo.platformsecurity.privacy.AccountCleanupAdapter.Outcome.SEPARATE_PROOF_REQUIRED||!(outcome==org.demo.platformsecurity.privacy.AccountCleanupAdapter.Outcome.MORE?code.equals("MORE_ROWS"):code.equals(work.service().equals("review-service")?"REVIEW_KEYS_RETAINED":"PRIVATE_ROWS_REMOVED")))throw new IllegalStateException("CLEANUP_FORMAT");
            return new org.demo.platformsecurity.privacy.AccountCleanupAdapter.Result(outcome,code);
        });
    }

    public org.demo.platformsecurity.privacy.CommerceCleanupAdapter.Batch commerceCleanup(PrivacyRequestStore.CommerceWork work){
        if(!PrivacyPlan.COMMERCE_SERVICES.contains(work.service()))throw new IllegalArgumentException("Invalid commerce scope");
        return client.post().uri(URI.create(origins.get(work.service())+"/internal/privacy/commerce-cleanup/"+work.customer())).header(org.demo.platformsecurity.privacy.JournalReceiptVerifier.HEADER,journalStore.journalHeader(work.request())).body(Map.of("requestId",work.request(),"cursor",work.cursor())).exchange((request,response)->{
            if(response.getStatusCode().value()!=200)throw new IllegalStateException("CLEANUP_UNAVAILABLE");
            byte[] raw=response.getBody().readNBytes(4097);if(raw.length>4096)throw new IllegalStateException("CLEANUP_FORMAT");var result=json.parse(raw);
            if(result.path("schemaVersion").asInt()!=1||!result.path("customerId").asText().equals(Long.toString(work.customer()))||!result.path("requestId").asText().equals(work.request().toString())||!result.path("service").asText().equals(work.service()))throw new IllegalStateException("CLEANUP_FORMAT");
            return new org.demo.platformsecurity.privacy.CommerceCleanupAdapter.Batch(Long.parseLong(result.path("nextCursor").asText()),result.path("outcome").asText(),result.path("code").asText());
        });
    }
}
