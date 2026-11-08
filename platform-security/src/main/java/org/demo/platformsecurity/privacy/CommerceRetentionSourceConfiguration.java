package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

/** Imported by the seven sources only; absent by default. Neither flag permits erasure. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="privacy.commerce-retention-source.enabled",havingValue="true",matchIfMissing=false)
public class CommerceRetentionSourceConfiguration {
    @Bean CommerceRetentionSourceOutbox commerceRetentionSourceOutbox(DataSource ds,PlatformTransactionManager manager){return new CommerceRetentionSourceOutbox(ds,manager);}
    @Bean CommerceRetentionSourceController commerceRetentionSourceController(CommerceRetentionSourceOutbox source){return new CommerceRetentionSourceController(source);}
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    @ConditionalOnProperty(name="privacy.commerce-retention-source.publisher-enabled",havingValue="true",matchIfMissing=false)
    static class Publication {
        @Bean CommerceRetentionInvalidationClient commerceRetentionInvalidationClient(
                @Value("${PLATFORM_ORDER_URL:${ORDER_SERVICE_URL:http://localhost:18082}}")String origin,
                @Value("${PLATFORM_SERVICE_TOKEN:}")String token,ServiceAuthenticationInterceptor auth){
            if(token.isBlank())throw new IllegalStateException("RETENTION_SOURCE_CREDENTIAL_REQUIRED");
            return new CommerceRetentionInvalidationClient(origin,auth);
        }
        @Bean SourcePublisher commerceRetentionSourcePublisher(CommerceRetentionSourceOutbox source,CommerceRetentionInvalidationClient client,
                @Value("${privacy.commerce-retention-source.publish-delay-ms:1000}")long delay){return new SourcePublisher(source,client,delay);}
    }
    public static final class SourcePublisher {
        private final CommerceRetentionSourceOutbox source;private final CommerceRetentionInvalidationClient client;
        public SourcePublisher(CommerceRetentionSourceOutbox source,CommerceRetentionInvalidationClient client,long delay){
            if(delay<100 || delay>60000)throw new IllegalArgumentException("Invalid retention publication interval");this.source=source;this.client=client;
        }
        @Scheduled(fixedDelayString="${privacy.commerce-retention-source.publish-delay-ms:1000}",initialDelayString="${privacy.commerce-retention-source.publish-delay-ms:1000}")
        public void tick(){try{source.publishOne(client);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(SourcePublisher.class).warn("RETENTION_INVALIDATION_TICK_FAILED");}}
    }
}
