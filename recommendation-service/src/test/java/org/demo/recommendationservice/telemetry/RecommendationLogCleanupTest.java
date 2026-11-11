package org.demo.recommendationservice.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.demo.recommendationservice.recommendation.RecommendationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.demo.recommendationservice.telemetry.RecommendationTelemetryTest.properties;

class RecommendationLogCleanupTest {
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private final RecommendationRequestRepository repository = mock(RecommendationRequestRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    // As in Spring, keep the owner of Micrometer's weak gauge references alive.
    private RecommendationLogCleanup activeWorker;
    private RecommendationLogCleanup worker(boolean enabled, int maxBatches) {
        activeWorker = new RecommendationLogCleanup(repository, properties(false, enabled, 2, maxBatches), registry, Clock.fixed(NOW, ZoneOffset.UTC));
        return activeWorker;
    }
    private double gauge(String suffix) {return registry.get("recommendation.request.log." + suffix).gauge().value();}

    @Test
    void disabledCleanupDoesNotQueryTheDatabaseOrPretendItsStateIsKnown() {
        worker(false, 3).cleanup(); verifyNoInteractions(repository);
        assertThat(gauge("cleanup.enabled")).isZero(); assertThat(gauge("cleanup.pending")).isEqualTo(-1);
        assertThat(gauge("oldest.age.seconds")).isEqualTo(-1);
    }

    @Test
    void eachBatchUsesOneUtcCutoffAndStopsAfterAShortBatch() {
        when(repository.deleteExpiredBatch(any(), eq(2))).thenReturn(2, 1);
        when(repository.oldestRequestedAt()).thenReturn(NOW.minusSeconds(60));
        worker(true, 4).cleanup();
        verify(repository, times(2)).deleteExpiredBatch(NOW.minusSeconds(604800), 2);
        assertThat(gauge("cleanup.pending")).isZero(); assertThat(gauge("cleanup.last.deleted")).isEqualTo(3);
        assertThat(gauge("oldest.age.seconds")).isEqualTo(60);
        assertThat(gauge("cleanup.last.success.timestamp.seconds")).isEqualTo(NOW.getEpochSecond());
        assertThat(registry.get("recommendation.request.log.deleted").counter().count()).isEqualTo(3);
    }

    @Test
    void maxBatchesIsEnforcedAndAnExpiredBacklogRemainsVisible() {
        when(repository.deleteExpiredBatch(any(), eq(2))).thenReturn(2);
        when(repository.oldestRequestedAt()).thenReturn(NOW.minusSeconds(604801));
        worker(true, 2).cleanup(); verify(repository, times(2)).deleteExpiredBatch(any(), eq(2));
        assertThat(gauge("cleanup.pending")).isEqualTo(1);
        assertThat(gauge("oldest.age.seconds")).isEqualTo(604801);
    }

    @Test
    void emptyTableIsKnownEmptyAndTheRetentionBoundaryIsNotExpired() {
        var worker = worker(true, 2);
        worker.cleanup(); assertThat(gauge("oldest.age.seconds")).isZero(); assertThat(gauge("cleanup.pending")).isZero();
        when(repository.oldestRequestedAt()).thenReturn(NOW.minusSeconds(604800));
        worker.cleanup(); assertThat(gauge("cleanup.pending")).isZero();
    }

    @Test
    void failureDoesNotReportAFalseSuccessOrEraseAlreadyCommittedDeletionCounts() {
        when(repository.deleteExpiredBatch(any(), eq(2))).thenReturn(2).thenThrow(new DataAccessResourceFailureException("fixture"));
        worker(true, 4).cleanup(); verify(repository, never()).oldestRequestedAt();
        assertThat(registry.get("recommendation.request.log.deleted").counter().count()).isEqualTo(2);
        assertThat(registry.get("recommendation.request.log.cleanup.runs").tag("outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(gauge("cleanup.last.success.timestamp.seconds")).isEqualTo(-1);
        assertThat(gauge("cleanup.last.deleted")).isEqualTo(-1);
    }

    @Test
    void aRepositoryFailureCanBeRecoveredOnTheNextRun() {
        when(repository.deleteExpiredBatch(any(), eq(2))).thenThrow(new DataAccessResourceFailureException("fixture")).thenReturn(0);
        var worker = worker(true, 4); worker.cleanup(); worker.cleanup();
        assertThat(gauge("cleanup.last.success.timestamp.seconds")).isEqualTo(NOW.getEpochSecond());
        assertThat(gauge("cleanup.pending")).isZero();
    }

    @Test
    void oldestReadFailureKeepsTheLastSnapshotUnknown() {
        when(repository.oldestRequestedAt()).thenThrow(new DataAccessResourceFailureException("fixture"));
        worker(true, 4).cleanup(); assertThat(gauge("cleanup.pending")).isEqualTo(-1);
        assertThat(gauge("cleanup.last.success.timestamp.seconds")).isEqualTo(-1);
    }
}
