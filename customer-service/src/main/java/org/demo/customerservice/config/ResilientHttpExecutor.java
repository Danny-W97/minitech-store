package org.demo.customerservice.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

@Component
public class ResilientHttpExecutor extends org.demo.platformhttp.ResilientHttpExecutor {
    public ResilientHttpExecutor(CircuitBreakerRegistry breakers, RetryRegistry retries,
                                BulkheadRegistry bulkheads) {
        super(breakers, retries, bulkheads);
    }
}
