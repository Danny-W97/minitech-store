package org.demo.orderservice.privacy;

import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.demo.platformsecurity.privacy.CommerceRetentionBindingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.orderservice.privacy.CommerceRetentionBindings.*;

class CommerceRetentionBindingConfigurationTest {
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(CommerceRetentionBindingConfiguration.class)
            .withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
            .withBean(ServiceAuthenticationInterceptor.class,()->new ServiceAuthenticationInterceptor("fixture"));}
    @Test void rootPublisherAndCoordinatorAreAbsentByDefault(){
        context().run(c->{assertThat(c).doesNotHaveBean(CommerceRetentionBindingPublisher.class);assertThat(c).doesNotHaveBean(CommerceRetentionBindings.class);});
        context().withPropertyValues("privacy.commerce-retention-binding.enabled=false").run(c->assertThat(c).doesNotHaveBean(CommerceRetentionBindingPublisher.class));
    }
    @Test void explicitFlagRequiresCredentialAndAcceptsOnlySafeOrigins(){
        context().withPropertyValues("privacy.commerce-retention-binding.enabled=true").run(c->assertThat(c).hasFailed());
        context().withPropertyValues("privacy.commerce-retention-binding.enabled=true","PLATFORM_SERVICE_TOKEN=fixture","privacy.commerce-retention-binding.publish-delay-ms=60000")
                .run(c->{assertThat(c).hasSingleBean(CommerceRetentionBindingPublisher.class);assertThat(c).hasSingleBean(CommerceRetentionBindings.class);});
        context().withPropertyValues("privacy.commerce-retention-binding.enabled=true","PLATFORM_SERVICE_TOKEN=fixture","privacy.commerce-retention-binding.origins.RETURNS=http://user:secret@localhost")
                .run(c->assertThat(c).hasFailed());
    }
    @Test void onlyOneExistingCommandIsDrainedPerTickAndNoPreparationOccurs(){
        var coordinator=mock(CommerceRetentionBindings.class);Map<Source,CommerceRetentionBindingClient> clients=new EnumMap<>(Source.class);
        for(var source:Source.values())clients.put(source,mock(CommerceRetentionBindingClient.class));
        var publisher=new CommerceRetentionBindingPublisher(coordinator,clients,1000);publisher.tick();verify(coordinator).publishOne(any());verifyNoMoreInteractions(coordinator);
        assertThatThrownBy(()->new CommerceRetentionBindingPublisher(coordinator,Map.of(),1000)).isInstanceOf(IllegalArgumentException.class);
    }
}
