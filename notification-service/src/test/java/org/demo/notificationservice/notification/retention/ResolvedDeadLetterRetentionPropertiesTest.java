package org.demo.notificationservice.notification.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvedDeadLetterRetentionPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(ResolvedDeadLetterRetentionConfiguration.class);
    @Test void defaultsObserveWithoutEnablingCompaction() {
        context.run(c -> {
            assertThat(c).hasNotFailed(); var p=c.getBean(ResolvedDeadLetterRetentionProperties.class);
            assertThat(p.mode()).isEqualTo(ResolvedDeadLetterRetentionProperties.Mode.OBSERVE);
            assertThat(p.retention()).isEqualTo(Duration.ofDays(30));
            assertThat(p.batchSize()).isEqualTo(500); assertThat(p.maxBatches()).isEqualTo(4);
        });
    }
    @Test void allExplicitModesAndAnIndependentDiagnosticAgeBind() {
        for(String mode:new String[]{"off","observe","compact"})
            context.withPropertyValues("notification.resolved-dlq-retention.mode="+mode,"notification.resolved-dlq-retention.retention=120d")
                    .run(c -> {assertThat(c).hasNotFailed();assertThat(c.getBean(ResolvedDeadLetterRetentionProperties.class).mode().name()).isEqualToIgnoringCase(mode);
                        assertThat(c.getBean(ResolvedDeadLetterRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(120));});
    }
    @Test void malformedOrUnboundedSettingsFailStartup() {
        for(String value:new String[]{"mode=delete","retention=0s","retention=23h","batch-size=0","batch-size=1001",
                "max-batches=0","max-batches=21","interval-ms=0","initial-delay-ms=-1"})
            context.withPropertyValues("notification.resolved-dlq-retention."+value).run(c -> assertThat(c).hasFailed());
    }
    @Test void dlqPolicyIsIndependentFromSentContentAndRejectedDiagnostics() {
        var both=context.withUserConfiguration(SentNotificationRetentionConfiguration.class,
                org.demo.notificationservice.messaging.retention.RejectedInboxRetentionConfiguration.class);
        for(String mode:new String[]{"off","compact"}) {
            String other=mode.equals("off")?"compact":"off";
            both.withPropertyValues("notification.resolved-dlq-retention.mode="+mode,"notification.resolved-dlq-retention.retention=120d",
                    "notification.sent-retention.mode="+other,"notification.sent-retention.retention=5d",
                    "messaging.rejected-inbox-retention.mode=observe").run(c -> {
                assertThat(c).hasNotFailed();var p=c.getBean(ResolvedDeadLetterRetentionProperties.class);
                assertThat(p.mode().name()).isEqualToIgnoringCase(mode);assertThat(p.retention()).isEqualTo(java.time.Duration.ofDays(120));
                var sent=c.getBean(SentNotificationRetentionProperties.class);assertThat(sent.mode().name()).isEqualToIgnoringCase(other);
                assertThat(sent.retention()).isEqualTo(java.time.Duration.ofDays(5));
                assertThat(c.getBean(org.demo.notificationservice.messaging.retention.RejectedInboxRetentionProperties.class).mode().name()).isEqualTo("OBSERVE");
            });
        }
    }
}
