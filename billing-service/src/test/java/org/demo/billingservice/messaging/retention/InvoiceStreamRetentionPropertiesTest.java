package org.demo.billingservice.messaging.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class InvoiceStreamRetentionPropertiesTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(InvoiceStreamRetentionConfiguration.class);
    @Test void defaultsObserveAndRequireAnExplicitRecipientPolicy() {
        context.run(c->{assertThat(c).hasNotFailed();var p=c.getBean(InvoiceStreamRetentionProperties.class);
            assertThat(p.mode()).isEqualTo(InvoiceStreamRetentionProperties.Mode.OBSERVE);assertThat(p.retention()).isEqualTo(Duration.ofDays(30));
            assertThat(p.groups()).isEmpty();assertThat(p.allowNoGroups()).isFalse();assertThat(p.batchSize()).isEqualTo(500);assertThat(p.maxBatches()).isEqualTo(4);});
    }
    @Test void explicitModesAndRecipientNamesBindWithoutCreatingConsumers() {
        for(String mode:new String[]{"off","observe","trim"})context.withPropertyValues("messaging.invoice-stream-retention.mode="+mode,
                "messaging.invoice-stream-retention.required-groups=analytics, archive","messaging.invoice-stream-retention.allow-no-groups=true")
                .run(c->{assertThat(c).hasNotFailed();var p=c.getBean(InvoiceStreamRetentionProperties.class);
                    assertThat(p.mode().name()).isEqualToIgnoringCase(mode);assertThat(p.groups()).containsExactly("analytics","archive");assertThat(p.allowNoGroups()).isTrue();});
    }
    @Test void malformedOrUnboundedSettingsFailStartup() {
        for(String value:new String[]{"mode=purge","retention=23h","retention=36501d","batch-size=0","batch-size=1001","max-batches=0","max-batches=21",
                "interval-ms=999","initial-delay-ms=-1","required-groups=a,,b","required-groups=a,a","required-groups=bad/name","allow-no-groups=maybe"})
            context.withPropertyValues("messaging.invoice-stream-retention."+value).run(c->assertThat(c).hasFailed());
        context.withPropertyValues("messaging.invoice-stream-retention.required-groups="+String.join(",",java.util.stream.IntStream.range(0,33).mapToObj(i->"g"+i).toList()))
                .run(c->assertThat(c).hasFailed());
    }
    @Test void streamAndDatabaseRetentionPoliciesRemainIndependent() {
        context.withUserConfiguration(InvoiceOutboxRetentionConfiguration.class,RejectedInboxRetentionConfiguration.class)
                .withPropertyValues("messaging.invoice-stream-retention.mode=trim","messaging.invoice-stream-retention.retention=120d",
                        "outbox.retention.mode=off","messaging.rejected-inbox-retention.mode=observe")
                .run(c->{assertThat(c).hasNotFailed();assertThat(c.getBean(InvoiceStreamRetentionProperties.class).mode()).isEqualTo(InvoiceStreamRetentionProperties.Mode.TRIM);
                    assertThat(c.getBean(InvoiceOutboxRetentionProperties.class).mode()).isEqualTo(InvoiceOutboxRetentionProperties.Mode.OFF);
                    assertThat(c.getBean(RejectedInboxRetentionProperties.class).mode()).isEqualTo(RejectedInboxRetentionProperties.Mode.OBSERVE);});
    }
}
