package org.demo.notificationservice.notification.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SentNotificationRetentionPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(SentNotificationRetentionConfiguration.class);
    @Test void defaultsObserveWithoutEnablingCompaction() {
        context.run(c -> {
            assertThat(c).hasNotFailed(); var p=c.getBean(SentNotificationRetentionProperties.class);
            assertThat(p.mode()).isEqualTo(SentNotificationRetentionProperties.Mode.OBSERVE);
            assertThat(p.retention()).isEqualTo(Duration.ofDays(30));
            assertThat(p.batchSize()).isEqualTo(500); assertThat(p.maxBatches()).isEqualTo(4);
        });
    }
    @Test void allExplicitModesAndAnIndependentDiagnosticAgeBind() {
        for(String mode:new String[]{"off","observe","compact"})
            context.withPropertyValues("notification.sent-retention.mode="+mode,"notification.sent-retention.retention=120d")
                    .run(c -> {assertThat(c).hasNotFailed();assertThat(c.getBean(SentNotificationRetentionProperties.class).mode().name()).isEqualToIgnoringCase(mode);
                        assertThat(c.getBean(SentNotificationRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(120));});
    }
    @Test void malformedOrUnboundedSettingsFailStartup() {
        for(String value:new String[]{"mode=delete","retention=0s","retention=23h","batch-size=0","batch-size=1001",
                "max-batches=0","max-batches=21","interval-ms=0","initial-delay-ms=-1"})
            context.withPropertyValues("notification.sent-retention."+value).run(c -> assertThat(c).hasFailed());
    }
    @Test void sentContentAndRejectedDiagnosticsHaveIndependentModesAndAges() {
        var both=context.withUserConfiguration(org.demo.notificationservice.messaging.retention.RejectedInboxRetentionConfiguration.class);
        for(String mode:new String[]{"off","compact"}) {
            String other=mode.equals("off")?"compact":"off";
            both.withPropertyValues("notification.sent-retention.mode="+mode,"notification.sent-retention.retention=120d",
                    "messaging.rejected-inbox-retention.mode="+other,"messaging.rejected-inbox-retention.retention=5d").run(c -> {
                assertThat(c).hasNotFailed();
                assertThat(c.getBean(SentNotificationRetentionProperties.class).mode().name()).isEqualToIgnoringCase(mode);
                assertThat(c.getBean(SentNotificationRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(120));
                var rejected=c.getBean(org.demo.notificationservice.messaging.retention.RejectedInboxRetentionProperties.class);
                assertThat(rejected.mode().name()).isEqualToIgnoringCase(other);assertThat(rejected.retention()).isEqualTo(Duration.ofDays(5));
            });
        }
    }
}
