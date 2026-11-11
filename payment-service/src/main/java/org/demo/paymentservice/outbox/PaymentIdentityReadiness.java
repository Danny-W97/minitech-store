package org.demo.paymentservice.outbox;

import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.demo.platformsecurity.privacy.FinanceHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Probe outside SQL transactions. A v2 event stays durable/pending while readers are old. */
@Component
public class PaymentIdentityReadiness {
    private final Map<String,FinanceHttp> readers;private volatile long validUntil;private volatile boolean ready;
    public PaymentIdentityReadiness(ServiceAuthenticationInterceptor auth,@Value("${BILLING_SERVICE_URL:http://localhost:18095}") String billing,@Value("${NOTIFICATION_SERVICE_URL:http://localhost:18086}") String notification){readers=Map.of("billing-service",new FinanceHttp(billing,auth),"notification-service",new FinanceHttp(notification,auth));}
    public synchronized boolean ready(){
        long now=System.nanoTime();if(now<validUntil)return ready;boolean supported=true;
        try{for(var reader:readers.entrySet()){var reply=reader.getValue().post("/internal/events/payment-identity-capability",Map.of());if(reply.path("schemaVersion").asInt()!=1||!reply.path("service").asText().equals(reader.getKey())||reply.path("identityVersion").asInt()!=2||!reply.path("dualReceipts").asBoolean())supported=false;}}
        catch(RuntimeException unavailable){supported=false;}
        ready=supported;validUntil=System.nanoTime()+(supported?5_000_000_000L:1_000_000_000L);return supported;
    }
}
