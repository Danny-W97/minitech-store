package org.demo.platformhttp;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.demo.platformsecurity.RequestBudget;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/** Opt-in implementation; call sites decide whether an operation may be retried. */
public class ResilientHttpExecutor {
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;

    public ResilientHttpExecutor(CircuitBreakerRegistry breakers, RetryRegistry retries,
                                BulkheadRegistry bulkheads) {
        this.breakers=breakers; this.retries=retries; this.bulkheads=bulkheads;
    }

    public <T> T execute(String serviceName, Supplier<T> operation) {
        return execute(serviceName, operation, true);
    }

    public <T> T execute(String serviceName, Supplier<T> operation, boolean retryEnabled) {
        var breaker=breakers.circuitBreaker(serviceName);
        var bulkhead=bulkheads.bulkhead(serviceName);
        Supplier<T> bounded=()->{ RequestBudget.check(); return operation.get(); };
        Supplier<T> retryable=retryEnabled ? Retry.decorateSupplier(retries.retry(serviceName), bounded) : bounded;
        var decorated=Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, retryable));
        try {
            return decorated.get();
        } catch (CallNotPermittedException | BulkheadFullException rejected) {
            throw new ResilienceRejectedException("Resilience policy rejected calls to "+serviceName, rejected);
        }
    }

    public static class ResilienceRejectedException extends RestClientException {
        public ResilienceRejectedException(String message, Throwable cause) { super(message, cause); }
    }
}
