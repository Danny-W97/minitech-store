package org.demo.orderservice.privacy;

import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.demo.platformsecurity.privacy.PeerRetentionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="privacy.commerce-retention-peer-evidence.enabled",havingValue="true",matchIfMissing=false)
public class CommerceRetentionPeerConfiguration {
    @Bean CommerceRetentionPeerCollector commerceRetentionPeerCollector(DataSource ds,PlatformTransactionManager manager){return new CommerceRetentionPeerCollector(ds,manager);}
    @Bean CommerceRetentionSourceGuardCollector commerceRetentionSourceGuardCollector(DataSource ds,PlatformTransactionManager manager){return new CommerceRetentionSourceGuardCollector(ds,manager);}
    @Bean Map<Participant,PeerRetentionClient> retentionPeerClients(Environment env,ServiceAuthenticationInterceptor auth,@Value("${PLATFORM_SERVICE_TOKEN:}")String token){
        if(token.isBlank())throw new IllegalStateException("PEER_EVIDENCE_CREDENTIAL_REQUIRED");var clients=new EnumMap<Participant,PeerRetentionClient>(Participant.class);
        Map<Participant,Integer> ports=Map.of(Participant.RETURNS,18094,Participant.PAYMENT,18084,Participant.PROMOTION,18089,Participant.DELIVERY,18088,Participant.BILLING,18095,Participant.NOTIFICATION,18086);
        ports.forEach((source,port)->clients.put(source,new PeerRetentionClient(env.getProperty(source.name()+"_SERVICE_URL","http://localhost:"+port),auth)));return Map.copyOf(clients);
    }
}
