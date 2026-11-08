package org.demo.recommendationservice.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class RecommendationLogPropertiesTest {
    @Test
    void defaultsBindWithLoggingDisabledAndBoundedCleanup() {
        new ApplicationContextRunner().withUserConfiguration(RecommendationLogConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(RecommendationLogProperties.class);
            assertThat(properties.enabled()).isFalse(); assertThat(properties.cleanupEnabled()).isTrue();
            assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.cleanupBatchSize()).isEqualTo(500); assertThat(properties.cleanupMaxBatches()).isEqualTo(4);
        });
    }

    @Test
    void invalidConfigurationFailsStartup() {
        for (String setting : new String[]{"retention=0s", "cleanup-batch-size=0", "cleanup-batch-size=1001",
                "cleanup-max-batches=21", "cleanup-interval-ms=0", "cleanup-initial-delay-ms=-1"}) {
            new ApplicationContextRunner().withUserConfiguration(RecommendationLogConfiguration.class)
                    .withPropertyValues("recommendation.request-log." + setting)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
