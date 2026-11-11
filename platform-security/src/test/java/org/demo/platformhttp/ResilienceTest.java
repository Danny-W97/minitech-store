package org.demo.platformhttp;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.demo.platformsecurity.RequestBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ResilienceTest {
    private final ResilienceConfiguration settings=new ResilienceConfiguration();

    private record Fixture(ResilientHttpExecutor executor, CircuitBreakerRegistry breakers, RetryRegistry retries) {}
    private Fixture fixture(int attempts,int concurrency) {
        var breakers=CircuitBreakerRegistry.of(settings.circuitBreakerConfig(20,5,50,10,2));
        var retries=RetryRegistry.of(settings.retryConfig(attempts,1,2));
        return new Fixture(new ResilientHttpExecutor(breakers,retries,BulkheadRegistry.of(settings.bulkheadConfig(concurrency,0))),breakers,retries);
    }
    private RestClientResponseException failure(int status) {
        return new RestClientResponseException("fixture",status,"fixture",null,null,null);
    }

    @ParameterizedTest @ValueSource(ints={500,502,503,504})
    void transientFailuresRetryButCircuitRecordsOneLogicalCall(int status) {
        var fixture=fixture(3,2);var attempts=new AtomicInteger();
        assertEquals("ok",fixture.executor.execute("dependency",()->{
            if(attempts.incrementAndGet()<3)throw failure(status);return "ok";
        }));
        assertEquals(3,attempts.get());
        var metrics=fixture.breakers.circuitBreaker("dependency").getMetrics();
        assertEquals(1,metrics.getNumberOfSuccessfulCalls());assertEquals(0,metrics.getNumberOfFailedCalls());
    }

    @ParameterizedTest @ValueSource(ints={400,401,403,404,409,422,429})
    void clientFailuresAreNeverRetriedOrCountedAsDependencyFailures(int status) {
        var fixture=fixture(3,2);var attempts=new AtomicInteger();
        assertThrows(RestClientResponseException.class,()->fixture.executor.execute("dependency",()->{
            attempts.incrementAndGet();throw failure(status);
        }));
        assertEquals(1,attempts.get());assertEquals(0,fixture.breakers.circuitBreaker("dependency").getMetrics().getNumberOfFailedCalls());
    }

    @Test void explicitNoRetryDoesNotCreateRetryStateOrRepeatAWritingOperation() {
        var fixture=fixture(3,2);var attempts=new AtomicInteger();
        assertThrows(RestClientException.class,()->fixture.executor.execute("write",()->{
            attempts.incrementAndGet();throw failure(503);
        },false));
        assertEquals(1,attempts.get());assertTrue(fixture.retries.getAllRetries().isEmpty());
    }

    @Test void exhaustedRetriesRecordOneFailureAndDependencyNamesRemainIndependent() {
        var fixture=fixture(3,2);var attempts=new AtomicInteger();
        assertThrows(RestClientException.class,()->fixture.executor.execute("down",()->{
            attempts.incrementAndGet();throw new RestClientException("offline");
        }));
        assertEquals(3,attempts.get());assertEquals(1,fixture.breakers.circuitBreaker("down").getMetrics().getNumberOfFailedCalls());
        assertEquals("ok",fixture.executor.execute("healthy",()->"ok"));
        fixture.breakers.circuitBreaker("down").transitionToOpenState();
        assertThrows(ResilientHttpExecutor.ResilienceRejectedException.class,()->fixture.executor.execute("down",()->{
            attempts.incrementAndGet();return "unexpected";
        }));
        assertEquals(3,attempts.get());assertEquals("ok",fixture.executor.execute("healthy",()->"ok"));
    }

    @Test void bulkheadRejectsWithoutExecutingAndReturnsItsPermitAfterCompletion() throws Exception {
        var fixture=fixture(3,1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var attempts=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()) {
            try {
                var running=pool.submit(()->fixture.executor.execute("limited",()->{
                    entered.countDown();
                    try { if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("fixture timeout"); }
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                    return "first";
                }));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertThrows(ResilientHttpExecutor.ResilienceRejectedException.class,()->fixture.executor.execute("limited",()->{
                    attempts.incrementAndGet();return "unexpected";
                }));
                assertEquals(0,attempts.get());release.countDown();assertEquals("first",running.get(5,TimeUnit.SECONDS));
                assertEquals("next",fixture.executor.execute("limited",()->"next"));
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void alreadyExpiredRequestNeverStartsTheOperation(boolean retry) {
        var fixture=fixture(3,2);var attempts=new AtomicInteger();
        try(var scope=RequestBudget.open(1000,"1")) {
            assertThrows(RequestBudget.Exceeded.class,()->fixture.executor.execute("expired",()->{
                attempts.incrementAndGet();return "unexpected";
            },retry));
        }
        assertEquals(0,attempts.get());
    }

    @Test void expiryDuringAFailedAttemptStopsFurtherAttempts() {
        var fixture=fixture(3,2);var attempts=new AtomicInteger();
        try(var scope=RequestBudget.open(100)) {
            assertThrows(RestClientException.class,()->fixture.executor.execute("slow",()->{
                attempts.incrementAndGet();
                try { Thread.sleep(150); } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                throw new RestClientException("slow failure");
            }));
        }
        assertEquals(1,attempts.get());
    }

    @Test void rejectionBudgetAndNonHttpErrorsAreNotTransient() {
        assertFalse(ResilienceConfiguration.isTransient(new ResilientHttpExecutor.ResilienceRejectedException("full",null)));
        assertFalse(ResilienceConfiguration.isTransient(new RequestBudget.Exceeded()));
        assertFalse(ResilienceConfiguration.isTransient(new IllegalArgumentException("bad input")));
        assertTrue(ResilienceConfiguration.isTransient(new RestClientException("offline")));
    }

    @Configuration(proxyBeanMethods=false)
    static class Adapter extends ResilienceConfiguration {}

    @Test void inheritedBeansHonorExistingPropertiesAndRemainLocalToTheApplication() {
        var runner=new ApplicationContextRunner().withUserConfiguration(Adapter.class);
        runner.withPropertyValues("resilience.retry.max-attempts=2","resilience.retry.initial-backoff-ms=7",
                "resilience.bulkhead.max-concurrent-calls=3","resilience.circuit-breaker.sliding-window-size=9").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(CircuitBreakerRegistry.class).hasSingleBean(RetryRegistry.class).hasSingleBean(BulkheadRegistry.class);
            assertEquals(2,context.getBean(RetryRegistry.class).retry("dependency").getRetryConfig().getMaxAttempts());
            assertEquals(3,context.getBean(BulkheadRegistry.class).bulkhead("dependency").getBulkheadConfig().getMaxConcurrentCalls());
            assertEquals(9,context.getBean(CircuitBreakerRegistry.class).circuitBreaker("dependency").getCircuitBreakerConfig().getSlidingWindowSize());
            for(String name:new String[]{"circuitBreakerMetrics","retryMetrics","bulkheadMetrics"})assertTrue(context.containsBean(name));
            runner.run(other->{
                assertThat(other).hasNotFailed();
                assertEquals(3,other.getBean(RetryRegistry.class).retry("dependency").getRetryConfig().getMaxAttempts());
                assertNotSame(context.getBean(RetryRegistry.class),other.getBean(RetryRegistry.class));
            });
        });
    }
}
