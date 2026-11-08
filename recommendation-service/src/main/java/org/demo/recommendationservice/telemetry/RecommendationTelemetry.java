package org.demo.recommendationservice.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.demo.recommendationservice.recommendation.RecommendationRequest;
import org.demo.recommendationservice.recommendation.RecommendationRequestRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;

@Component
public class RecommendationTelemetry {
    public enum Strategy { PRODUCT_SIMILARITY, CUSTOMER_HISTORY }
    private record Meters(Counter responses, DistributionSummary results) { }
    private final RecommendationRequestRepository repository;
    private final RecommendationLogProperties properties;
    private final EnumMap<Strategy, Meters> meters = new EnumMap<>(Strategy.class);
    private final Counter writes, failures;

    public RecommendationTelemetry(RecommendationRequestRepository repository,
                                   RecommendationLogProperties properties, MeterRegistry registry) {
        this.repository = repository;
        this.properties = properties;
        for (Strategy strategy : Strategy.values()) {
            meters.put(strategy, new Meters(
                    registry.counter("recommendation.responses", "strategy", strategy.name()),
                    DistributionSummary.builder("recommendation.result.count")
                            .tag("strategy", strategy.name()).register(registry)));
        }
        writes = registry.counter("recommendation.request.log.writes", "outcome", "success");
        failures = registry.counter("recommendation.request.log.writes", "outcome", "failed");
        Gauge.builder("recommendation.request.log.enabled", properties, p -> p.enabled() ? 1 : 0).register(registry);
    }

    public void record(Strategy strategy, Long customerId, Long productId, int resultCount) {
        Meters current = meters.get(strategy);
        current.responses().increment();
        current.results().record(resultCount);
        if (!properties.enabled()) return;
        try {
            // The repository owns this short transaction; no remote HTTP is inside it.
            repository.save(new RecommendationRequest(customerId, productId, strategy.name(), resultCount));
            writes.increment();
        } catch (DataAccessException failure) {
            failures.increment();
            // No per-request warning flood or SQL/identifier disclosure; expose the counter instead.
        }
    }
}
