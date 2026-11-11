package org.demo.orderservice.privacy;

import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.demo.platformsecurity.privacy.InventoryRetentionClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceRetentionInventoryConfigurationTest {
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(CommerceRetentionInventoryConfiguration.class)
        .withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
        .withBean(ServiceAuthenticationInterceptor.class,()->new ServiceAuthenticationInterceptor("fixture"));}
    @Test void absentAndFalseFlagsCreateNoCollectorOrClient(){for(String value:new String[]{"","false"}){var runner=context();if(!value.isEmpty())runner=runner.withPropertyValues("privacy.commerce-retention-inventory-evidence.enabled="+value);runner.run(c->{assertThat(c).doesNotHaveBean(CommerceRetentionInventoryCollector.class);assertThat(c).doesNotHaveBean(InventoryRetentionClient.class);});}}
    @Test void explicitEnablementNeedsTheExistingCredential(){context().withPropertyValues("privacy.commerce-retention-inventory-evidence.enabled=true").run(c->assertThat(c).hasFailed());}
    @Test void explicitConfigurationCreatesOnlyInvokedComponents(){context().withPropertyValues("privacy.commerce-retention-inventory-evidence.enabled=true","PLATFORM_SERVICE_TOKEN=fixture").run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(CommerceRetentionInventoryCollector.class);assertThat(c).hasSingleBean(InventoryRetentionClient.class);assertThat(c).doesNotHaveBean("scheduledAnnotationProcessor");});}
}
