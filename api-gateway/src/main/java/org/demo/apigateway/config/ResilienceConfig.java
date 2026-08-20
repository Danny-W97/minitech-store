package org.demo.apigateway.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    @Bean
    CircuitBreakerConfig circuitBreakerConfig(
            @Value("${resilience.circuit-breaker.sliding-window-size:20}") int slidingWindowSize,
            @Value("${resilience.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${resilience.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${resilience.circuit-breaker.wait-duration-open-seconds:10}") long waitDurationOpenSeconds,
            @Value("${resilience.circuit-breaker.half-open-permitted-calls:2}") int halfOpenPermittedCalls
    ) {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationOpenSeconds))
                .permittedNumberOfCallsInHalfOpenState(halfOpenPermittedCalls)
                .recordException(ResilienceConfig::isTransient)
                .build();
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(CircuitBreakerConfig config) {
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    RetryConfig retryConfig(
            @Value("${resilience.retry.max-attempts:3}") int maxAttempts,
            @Value("${resilience.retry.initial-backoff-ms:200}") long initialBackoffMs,
            @Value("${resilience.retry.backoff-multiplier:2.0}") double backoffMultiplier
    ) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(initialBackoffMs, backoffMultiplier))
                .retryOnException(ResilienceConfig::isTransient)
                .build();
    }

    @Bean
    RetryRegistry retryRegistry(RetryConfig config) {
        return RetryRegistry.of(config);
    }

    @Bean
    BulkheadConfig bulkheadConfig(
            @Value("${resilience.bulkhead.max-concurrent-calls:50}") int maxConcurrentCalls,
            @Value("${resilience.bulkhead.max-wait-duration-ms:0}") long maxWaitDurationMs
    ) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ofMillis(maxWaitDurationMs))
                .build();
    }

    @Bean
    BulkheadRegistry bulkheadRegistry(BulkheadConfig config) {
        return BulkheadRegistry.of(config);
    }

    @Bean
    MeterBinder circuitBreakerMetrics(CircuitBreakerRegistry registry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    }

    @Bean
    MeterBinder retryMetrics(RetryRegistry registry) {
        return TaggedRetryMetrics.ofRetryRegistry(registry);
    }

    @Bean
    MeterBinder bulkheadMetrics(BulkheadRegistry registry) {
        return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
    }

    static boolean isTransient(Throwable throwable) {
        if (throwable instanceof ResilientHttpExecutor.ResilienceRejectedException) {
            return false;
        }
        if (throwable instanceof org.springframework.web.client.RestClientResponseException responseException) {
            return responseException.getStatusCode().is5xxServerError();
        }
        return throwable instanceof org.springframework.web.client.RestClientException;
    }
}
