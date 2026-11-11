package org.demo.recommendationservice.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.recommendationservice.recommendation.RecommendationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.demo.recommendationservice.telemetry.RecommendationTelemetry.Strategy.*;

class RecommendationTelemetryTest {
    private RecommendationTelemetry activeTelemetry;
    static RecommendationLogProperties properties(boolean enabled, boolean cleanup, int batch, int batches) {
        return new RecommendationLogProperties(enabled, cleanup, Duration.ofDays(7), batch, batches, 600000, 60000);
    }

    @Test
    void defaultTelemetryAggregatesBothStrategiesWithoutPersistenceOrIdentifierLabels() {
        var repository = mock(RecommendationRequestRepository.class);
        var registry = new SimpleMeterRegistry();
        var telemetry = activeTelemetry = new RecommendationTelemetry(repository, properties(false, true, 500, 4), registry);
        for (long customer = 1; customer <= 100; customer++) telemetry.record(CUSTOMER_HISTORY, customer, null, 3);
        telemetry.record(PRODUCT_SIMILARITY, null, 9001L, 2);
        verifyNoInteractions(repository);
        assertThat(registry.get("recommendation.responses").tag("strategy", "CUSTOMER_HISTORY").counter().count()).isEqualTo(100);
        assertThat(registry.get("recommendation.result.count").tag("strategy", "CUSTOMER_HISTORY").summary().totalAmount()).isEqualTo(300);
        assertThat(registry.get("recommendation.request.log.enabled").gauge().value()).isZero();
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("strategy", "outcome")));
        assertThat(registry.getMeters()).hasSize(7);
    }

    @Test
    void explicitDiagnosticLoggingPersistsAndCountsOnlySuccessfulWrites() {
        var repository = mock(RecommendationRequestRepository.class);
        var registry = new SimpleMeterRegistry();
        var telemetry = activeTelemetry = new RecommendationTelemetry(repository, properties(true, true, 500, 4), registry);
        telemetry.record(CUSTOMER_HISTORY, 17L, null, 2);
        telemetry.record(PRODUCT_SIMILARITY, null, 1L, 3);
        verify(repository, times(2)).save(any());
        assertThat(registry.get("recommendation.request.log.writes").tag("outcome", "success").counter().count()).isEqualTo(2);
    }

    @Test
    void diagnosticFailureIsCountedWithoutBreakingTheRecommendation() {
        var repository = mock(RecommendationRequestRepository.class);
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("fixture sensitive SQL"));
        var registry = new SimpleMeterRegistry();
        var telemetry = activeTelemetry = new RecommendationTelemetry(repository, properties(true, true, 500, 4), registry);
        assertThatCode(() -> telemetry.record(CUSTOMER_HISTORY, 17L, null, 2)).doesNotThrowAnyException();
        assertThat(registry.get("recommendation.request.log.writes").tag("outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(registry.get("recommendation.request.log.writes").tag("outcome", "success").counter().count()).isZero();
    }
}
