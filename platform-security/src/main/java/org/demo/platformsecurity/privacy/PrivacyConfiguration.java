package org.demo.platformsecurity.privacy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

@AutoConfiguration(afterName="org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(name="org.springframework.jdbc.core.JdbcTemplate")
public class PrivacyConfiguration {
    @Bean JournalReceiptVerifier journalReceiptVerifier(@Value("${privacy.restore-journal.public-key-base64:}") String key,@Value("${privacy.restore-journal.ledger-id:}") String ledger){return new JournalReceiptVerifier(key,ledger);}
    @Bean static BeanPostProcessor privacyDataSources() {
        return new BeanPostProcessor(){@Override public Object postProcessAfterInitialization(Object bean,String name){return bean instanceof DataSource source?PrivacyDataSources.wrap(source):bean;}};
    }
    @Bean @ConditionalOnBean(DataSource.class)
    PrivacyFenceStore privacyFenceStore(DataSource source,PlatformTransactionManager manager){return new PrivacyFenceStore(source,manager);}
    @Bean @ConditionalOnBean(DataSource.class)
    PrivacySql privacySql(DataSource source){return new PrivacySql(source);}
    @Bean @ConditionalOnBean(PrivacyFenceStore.class)
    PrivacyFenceController privacyFenceController(PrivacyFenceStore store,@Value("${spring.application.name:unknown-service}") String service){return new PrivacyFenceController(store,service);}
    @Bean @ConditionalOnBean(AccountCleanupAdapter.class)
    AccountCleanupController accountCleanupController(AccountCleanupAdapter adapter,@Value("${privacy.account-cleanup.enabled:false}") boolean enabled,@Value("${spring.application.name:unknown-service}") String service){return new AccountCleanupController(adapter,enabled,service);}
    @Bean @ConditionalOnBean(CommerceCleanupAdapter.class) @ConditionalOnMissingBean(CommerceOrderSource.class)
    CommerceOrderSource commerceOrderSource(@Value("${ORDER_SERVICE_URL:http://localhost:18082}") String origin,org.demo.platformsecurity.ServiceAuthenticationInterceptor authentication){return new CommerceOrderHttpSource(origin,authentication);}
    @Bean @ConditionalOnBean(CommerceCleanupAdapter.class)
    CommerceCleanupController commerceCleanupController(CommerceCleanupAdapter adapter,@Value("${privacy.commerce-cleanup.enabled:false}") boolean enabled,@Value("${spring.application.name:unknown-service}") String service){return new CommerceCleanupController(adapter,enabled,service);}
    @Bean @ConditionalOnBean(FinancePreparationAdapter.class) @ConditionalOnMissingBean(CommerceOrderSource.class)
    CommerceOrderSource financeOrderSource(@Value("${ORDER_SERVICE_URL:http://localhost:18082}") String origin,org.demo.platformsecurity.ServiceAuthenticationInterceptor authentication){return new CommerceOrderHttpSource(origin,authentication);}
    @Bean @ConditionalOnBean(FinancePreparationAdapter.class)
    FinancePreparationController financePreparationController(FinancePreparationAdapter adapter,@Value("${privacy.finance-cleanup.enabled:false}") boolean enabled,@Value("${spring.application.name:unknown-service}") String service){return new FinancePreparationController(adapter,enabled,service);}
}
