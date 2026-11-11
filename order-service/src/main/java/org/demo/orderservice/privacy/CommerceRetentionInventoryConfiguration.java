package org.demo.orderservice.privacy;

import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.demo.platformsecurity.privacy.InventoryRetentionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

/** Explicitly invoked collection only. Does not scan or prepare generations. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="privacy.commerce-retention-inventory-evidence.enabled",havingValue="true",matchIfMissing=false)
public class CommerceRetentionInventoryConfiguration {
    @Bean CommerceRetentionInventoryCollector commerceRetentionInventoryCollector(DataSource ds,PlatformTransactionManager manager){return new CommerceRetentionInventoryCollector(ds,manager);}
    @Bean InventoryRetentionClient inventoryRetentionClient(ServiceAuthenticationInterceptor auth,
            @Value("${INVENTORY_SERVICE_URL:http://localhost:18083}")String origin,@Value("${PLATFORM_SERVICE_TOKEN:}")String token){
        if(token.isBlank())throw new IllegalStateException("INVENTORY_EVIDENCE_CREDENTIAL_REQUIRED");return new InventoryRetentionClient(origin,auth);
    }
}
