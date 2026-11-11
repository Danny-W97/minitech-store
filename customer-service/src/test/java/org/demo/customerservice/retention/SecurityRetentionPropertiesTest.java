package org.demo.customerservice.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityRetentionPropertiesTest {
    @Test void defaultsBindToBoundedSevenDayRetention() {
        new ApplicationContextRunner().withUserConfiguration(SecurityRetentionConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();var p=context.getBean(SecurityRetentionProperties.class);
            assertThat(p.enabled()).isTrue();assertThat(p.retention()).isEqualTo(Duration.ofDays(7));
            assertThat(p.batchSize()).isEqualTo(100);assertThat(p.maxBatchesPerStore()).isEqualTo(4);
        });
    }
    @Test void invalidSettingsFailStartup() {
        for(String value:new String[]{"retention=0s","batch-size=0","batch-size=1001","max-batches-per-store=0",
                "max-batches-per-store=21","interval-ms=0","initial-delay-ms=-1"})
            new ApplicationContextRunner().withUserConfiguration(SecurityRetentionConfiguration.class)
                    .withPropertyValues("customer.security-retention."+value).run(context -> assertThat(context).hasFailed());
    }
}
