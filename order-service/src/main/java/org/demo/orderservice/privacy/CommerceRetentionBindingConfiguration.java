package org.demo.orderservice.privacy;

import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.demo.platformsecurity.privacy.CommerceRetentionBindingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import static org.demo.orderservice.privacy.CommerceRetentionBindings.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="privacy.commerce-retention-binding.enabled",havingValue="true",matchIfMissing=false)
public class CommerceRetentionBindingConfiguration {
    @Bean CommerceRetentionBindings commerceRetentionBindings(DataSource ds,PlatformTransactionManager manager){return new CommerceRetentionBindings(ds,manager);}
    @Bean CommerceRetentionBindingPublisher commerceRetentionBindingPublisher(CommerceRetentionBindings coordinator,Environment env,
            ServiceAuthenticationInterceptor auth,@Value("${PLATFORM_SERVICE_TOKEN:}")String token,
            @Value("${privacy.commerce-retention-binding.publish-delay-ms:1000}")long delay){
        if(token.isBlank())throw new IllegalStateException("RETENTION_BINDING_CREDENTIAL_REQUIRED");
        Map<Source,Integer> ports=Map.of(Source.RETURNS,18094,Source.PAYMENT,18084,Source.INVENTORY,18083,
                Source.PROMOTION,18089,Source.DELIVERY,18088,Source.BILLING,18095,Source.NOTIFICATION,18086);
        Map<Source,CommerceRetentionBindingClient> clients=new EnumMap<>(Source.class);
        for(var source:Source.values()){
            String origin=env.getProperty("privacy.commerce-retention-binding.origins."+source.name(),
                    env.getProperty(source.name()+"_SERVICE_URL","http://localhost:"+ports.get(source)));
            clients.put(source,new CommerceRetentionBindingClient(origin,auth));
        }
        return new CommerceRetentionBindingPublisher(coordinator,clients,delay);
    }
}
