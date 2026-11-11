package org.demo.notificationservice.messaging.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RejectedInboxRetentionPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(RejectedInboxRetentionConfiguration.class);
    @Test void defaultsObserveWithoutEnablingCompaction() {
        context.run(c -> {
            assertThat(c).hasNotFailed(); var p=c.getBean(RejectedInboxRetentionProperties.class);
            assertThat(p.mode()).isEqualTo(RejectedInboxRetentionProperties.Mode.OBSERVE);
            assertThat(p.retention()).isEqualTo(Duration.ofDays(30));
            assertThat(p.batchSize()).isEqualTo(500); assertThat(p.maxBatches()).isEqualTo(4);
        });
    }
    @Test void allExplicitModesAndAnIndependentDiagnosticAgeBind() {
        for(String mode:new String[]{"off","observe","compact"})
            context.withPropertyValues("messaging.rejected-inbox-retention.mode="+mode,"messaging.rejected-inbox-retention.retention=120d")
                    .run(c -> {assertThat(c).hasNotFailed();assertThat(c.getBean(RejectedInboxRetentionProperties.class).mode().name()).isEqualToIgnoringCase(mode);
                        assertThat(c.getBean(RejectedInboxRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(120));});
    }
    @Test void malformedOrUnboundedSettingsFailStartup() {
        for(String value:new String[]{"mode=delete","retention=0s","retention=23h","batch-size=0","batch-size=1001",
                "max-batches=0","max-batches=21","interval-ms=0","initial-delay-ms=-1"})
            context.withPropertyValues("messaging.rejected-inbox-retention."+value).run(c -> assertThat(c).hasFailed());
    }
}
