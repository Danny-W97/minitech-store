package org.demo.customerservice.audit.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRetentionPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(AuditRetentionConfiguration.class);
    @Test void defaultsObserveWithoutEnablingPurge() {
        context.run(c -> {
            assertThat(c).hasNotFailed(); var p=c.getBean(AuditRetentionProperties.class);
            assertThat(p.mode()).isEqualTo(AuditRetentionProperties.Mode.OBSERVE);
            assertThat(p.retention()).isEqualTo(Duration.ofDays(90));
            assertThat(p.batchSize()).isEqualTo(500); assertThat(p.maxBatches()).isEqualTo(4);
        });
    }
    @Test void allExplicitModesAndAnIndependentAuditAgeBind() {
        for(String mode:new String[]{"off","observe","purge"})
            context.withPropertyValues("customer.audit-retention.mode="+mode,"customer.audit-retention.retention=120d")
                    .run(c -> {assertThat(c).hasNotFailed();assertThat(c.getBean(AuditRetentionProperties.class).mode().name()).isEqualToIgnoringCase(mode);
                        assertThat(c.getBean(AuditRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(120));});
    }
    @Test void malformedOrUnboundedSettingsFailStartup() {
        for(String value:new String[]{"mode=delete","retention=0s","retention=23h","batch-size=0","batch-size=1001",
                "max-batches=0","max-batches=21","interval-ms=0","initial-delay-ms=-1"})
            context.withPropertyValues("customer.audit-retention."+value).run(c -> assertThat(c).hasFailed());
    }
}
