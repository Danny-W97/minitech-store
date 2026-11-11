package org.demo.paymentservice.outbox.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxRetentionPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(PaymentOutboxRetentionConfiguration.class);
    @Test void defaultsObserveWithoutEnablingCompaction() {
        context.run(c -> {
            assertThat(c).hasNotFailed(); var p=c.getBean(PaymentOutboxRetentionProperties.class);
            assertThat(p.mode()).isEqualTo(PaymentOutboxRetentionProperties.Mode.OBSERVE);
            assertThat(p.retention()).isEqualTo(Duration.ofDays(30));
            assertThat(p.batchSize()).isEqualTo(500); assertThat(p.maxBatches()).isEqualTo(4);
        });
    }
    @Test void allExplicitModesAndAnIndependentOutboxAgeBind() {
        for(String mode:new String[]{"off","observe","compact"})
            context.withPropertyValues("outbox.retention.mode="+mode,"outbox.retention.retention=120d")
                    .run(c -> {assertThat(c).hasNotFailed();assertThat(c.getBean(PaymentOutboxRetentionProperties.class).mode().name()).isEqualToIgnoringCase(mode);
                        assertThat(c.getBean(PaymentOutboxRetentionProperties.class).retention()).isEqualTo(Duration.ofDays(120));});
    }
    @Test void malformedOrUnboundedSettingsFailStartup() {
        for(String value:new String[]{"mode=delete","retention=0s","retention=23h","batch-size=0","batch-size=1001",
                "max-batches=0","max-batches=21","interval-ms=0","initial-delay-ms=-1"})
            context.withPropertyValues("outbox.retention."+value).run(c -> assertThat(c).hasFailed());
    }
}
