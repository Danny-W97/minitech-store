package org.demo.apigateway.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ResilientHttpExecutor {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final io.github.resilience4j.bulkhead.BulkheadRegistry bulkheadRegistry;

    public ResilientHttpExecutor(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            io.github.resilience4j.bulkhead.BulkheadRegistry bulkheadRegistry
    ) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
    }

    public <T> T execute(String serviceName, Supplier<T> operation) {
        return execute(serviceName, operation, true);
    }

    public <T> T execute(String serviceName, Supplier<T> operation, boolean retryEnabled) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(serviceName);
        Supplier<T> retryableOperation = operation;
        if (retryEnabled) {
            Retry retry = retryRegistry.retry(serviceName);
            retryableOperation = Retry.decorateSupplier(retry, operation);
        }
        Supplier<T> decorated = Bulkhead.decorateSupplier(
                bulkhead,
                CircuitBreaker.decorateSupplier(
                        circuitBreaker,
                        retryableOperation
                )
        );
        try {
            return decorated.get();
        } catch (CallNotPermittedException | BulkheadFullException exception) {
            throw new ResilienceRejectedException(
                    "Resilience policy rejected calls to " + serviceName,
                    exception
            );
        }
    }

    public static class ResilienceRejectedException extends org.springframework.web.client.RestClientException {
        public ResilienceRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
