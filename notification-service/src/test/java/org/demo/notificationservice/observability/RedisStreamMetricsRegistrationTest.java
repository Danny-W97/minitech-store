package org.demo.notificationservice.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import org.demo.platformobservability.RedisStreamMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisStreamMetricsRegistrationTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final StreamOperations<String,Object,Object> streams=mock(StreamOperations.class);
    ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(BusinessMetricsConfiguration.class)
                .withBean(StringRedisTemplate.class,()->redis)
                .withBean(JdbcTemplate.class,()->new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:metrics-registration","sa","")));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void collectorUsesConsumerPropertiesAndIsAbsentWhenIntentionallyDisabled(boolean enabled) {
        when(redis.<Object,Object>opsForStream()).thenReturn(streams);
        when(streams.groups("custom-fixture-stream")).thenReturn(StreamInfo.XInfoGroups.fromList(List.of(
                List.of("name","custom-fixture-group","pending",7L,"lag",11L))));
        context().withPropertyValues("messaging.consumer.enabled="+enabled,"messaging.payment-stream=custom-fixture-stream",
                "messaging.consumer.group=custom-fixture-group").run(ctx->{
            assertThat(ctx).hasNotFailed();
            if(!enabled) {assertThat(ctx).doesNotHaveBean(RedisStreamMetrics.class);verify(redis,never()).opsForStream();verifyNoInteractions(streams);return;}
            assertThat(ctx).hasSingleBean(RedisStreamMetrics.class);
            var registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            try {
                var metrics=ctx.getBean(RedisStreamMetrics.class);metrics.bindTo(registry);
                assertThat(registry.scrape()).contains("platform_redis_stream_pending", "NaN").doesNotContain("custom-fixture");
                verify(redis,never()).opsForStream();verifyNoInteractions(streams);metrics.collect();
                assertThat(registry.get("platform.redis.stream.pending").tag("recipient","notification").gauge().value()).isEqualTo(7);
                assertThat(registry.get("platform.redis.stream.lag").tag("stream","payments").gauge().value()).isEqualTo(11);
                assertThat(registry.getMeters()).allSatisfy(meter->assertThat(meter.getId().toString()).doesNotContain("custom-fixture"));
                assertThat(registry.scrape()).contains("platform_redis_stream_pending", "platform_redis_stream_lag").doesNotContain("custom-fixture");
                verify(redis).opsForStream();verify(streams).groups("custom-fixture-stream");verifyNoMoreInteractions(streams);
            } finally {registry.close();}
        });
    }
    @Test void missingEnabledFlagPreservesTheExistingConsumerDefault() {
        context().withPropertyValues("messaging.payment-stream=fixture-stream").run(ctx->{
            assertThat(ctx).hasNotFailed().hasSingleBean(RedisStreamMetrics.class);verify(redis,never()).opsForStream();
        });
    }
}
