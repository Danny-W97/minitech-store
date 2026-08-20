package org.demo.orderservice.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilientHttpExecutorFailureTest {

    @Test
    void retriesTransientFailureWithBackoffPolicy() {
        ResilientHttpExecutor executor = executor(
                CircuitBreakerConfig.custom().slidingWindowSize(20).minimumNumberOfCalls(10).build(),
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(1))
                        .retryOnException(throwable -> throwable instanceof RestClientException)
                        .build(),
                BulkheadConfig.custom().maxConcurrentCalls(5).build()
        );
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute("retry-test", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RestClientException("temporary dependency failure");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void opensCircuitAndRejectsSubsequentCalls() {
        ResilientHttpExecutor executor = executor(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .build(),
                RetryConfig.custom().maxAttempts(1).build(),
                BulkheadConfig.custom().maxConcurrentCalls(5).build()
        );

        assertThrows(RestClientException.class, () -> executor.execute(
                "circuit-breaker-test", () -> { throw new RestClientException("downstream unavailable"); }
        ));
        assertThrows(RestClientException.class, () -> executor.execute(
                "circuit-breaker-test", () -> { throw new RestClientException("downstream unavailable"); }
        ));

        assertThrows(
                ResilientHttpExecutor.ResilienceRejectedException.class,
                () -> executor.execute("circuit-breaker-test", () -> "must not execute")
        );
    }

    @Test
    void rejectsConcurrentCallWhenBulkheadIsFull() throws Exception {
        ResilientHttpExecutor executor = executor(
                CircuitBreakerConfig.custom().slidingWindowSize(20).minimumNumberOfCalls(10).build(),
                RetryConfig.custom().maxAttempts(1).build(),
                BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()
        );
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            Future<String> first = pool.submit(() -> executor.execute("bulkhead-test", () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Bulkhead test was interrupted", exception);
                }
                return "first";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertThrows(
                    ResilientHttpExecutor.ResilienceRejectedException.class,
                    () -> executor.execute("bulkhead-test", () -> "second")
            );

            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private ResilientHttpExecutor executor(
            CircuitBreakerConfig circuitBreakerConfig,
            RetryConfig retryConfig,
            BulkheadConfig bulkheadConfig
    ) {
        return new ResilientHttpExecutor(
                CircuitBreakerRegistry.of(circuitBreakerConfig),
                RetryRegistry.of(retryConfig),
                BulkheadRegistry.of(bulkheadConfig)
        );
    }
}
