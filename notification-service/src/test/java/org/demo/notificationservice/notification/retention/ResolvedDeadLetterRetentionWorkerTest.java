package org.demo.notificationservice.notification.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.demo.notificationservice.notification.retention.ResolvedDeadLetterRetentionProperties.Mode.*;

class ResolvedDeadLetterRetentionWorkerTest {
    static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z"), CUTOFF=NOW.minus(Duration.ofDays(30));
    private final ResolvedDeadLetterRetentionRepository repository=mock(ResolvedDeadLetterRetentionRepository.class);
    private final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    private ResolvedDeadLetterRetentionWorker activeWorker;
    static ResolvedDeadLetterRetentionProperties properties(ResolvedDeadLetterRetentionProperties.Mode mode,int maxBatches) {
        return new ResolvedDeadLetterRetentionProperties(mode,Duration.ofDays(30),2,maxBatches,600000,60000);
    }
    private ResolvedDeadLetterRetentionWorker worker(ResolvedDeadLetterRetentionProperties.Mode mode,int maxBatches) {
        activeWorker=new ResolvedDeadLetterRetentionWorker(repository,properties(mode,maxBatches),registry,Clock.fixed(NOW,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(String name) {return registry.get("notification.resolved.dlq.retention."+name).gauge().value();}
    private double counter(String name) {return registry.get("notification.resolved.dlq.retention."+name).counter().count();}

    @Test void offDoesNotAccessTheDatabaseOrPretendUnknownDataIsEmpty() {
        worker(OFF,4).cleanup();verifyNoInteractions(repository);
        assertThat(gauge("enabled")).isZero();assertThat(gauge("compaction.enabled")).isZero();
        assertThat(gauge("pending")).isEqualTo(-1);assertThat(gauge("last.success.timestamp.seconds")).isEqualTo(-1);
    }
    @Test void observeReportsOldDataWithoutIssuingPayloadUpdates() {
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(5));
        worker(OBSERVE,4).cleanup();verify(repository).oldestEligible(CUTOFF);verifyNoMoreInteractions(repository);
        assertThat(gauge("compaction.enabled")).isZero();assertThat(gauge("enabled")).isEqualTo(1);
        assertThat(gauge("pending")).isEqualTo(1);assertThat(gauge("oldest.eligible.age.seconds")).isEqualTo(2592005);
        assertThat(gauge("last.compacted")).isZero();assertThat(counter("compacted")).isZero();
    }
    @Test void compactUsesOneCutoffAndStopsAtItsBatchBudget() {
        when(repository.compactBatch(CUTOFF,NOW,2)).thenReturn(2);
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(1));
        worker(COMPACT,3).cleanup();verify(repository,times(3)).compactBatch(CUTOFF,NOW,2);
        assertThat(counter("compacted")).isEqualTo(6);assertThat(gauge("last.compacted")).isEqualTo(6);
        assertThat(gauge("compaction.enabled")).isEqualTo(1);assertThat(gauge("pending")).isEqualTo(1);
    }
    @Test void aShortOrLockedBatchStopsButDoesNotHideTheEligibleBacklog() {
        when(repository.compactBatch(CUTOFF,NOW,2)).thenReturn(0);
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(1));
        worker(COMPACT,4).cleanup();verify(repository).compactBatch(CUTOFF,NOW,2);
        assertThat(gauge("pending")).isEqualTo(1);assertThat(gauge("last.compacted")).isZero();
    }
    @Test void laterTransactionFailurePreservesCommittedCountsAndPreviousSnapshotThenRecovers() {
        when(repository.compactBatch(CUTOFF,NOW,2)).thenReturn(0,2).thenThrow(new CannotCreateTransactionException("fixture")).thenReturn(0);
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(5),null);
        var worker=worker(COMPACT,4);worker.cleanup();worker.cleanup();
        assertThat(counter("compacted")).isEqualTo(2);assertThat(gauge("pending")).isEqualTo(1);
        assertThat(gauge("last.compacted")).isZero();assertThat(gauge("last.success.timestamp.seconds")).isEqualTo(NOW.getEpochSecond());
        assertThat(registry.get("notification.resolved.dlq.retention.runs").tag("outcome","failed").counter().count()).isEqualTo(1);
        worker.cleanup();assertThat(gauge("pending")).isZero();assertThat(counter("compacted")).isEqualTo(2);
    }
    @Test void snapshotFailureCannotAnnounceSuccessOrEraseCommittedCompactions() {
        when(repository.compactBatch(CUTOFF,NOW,2)).thenReturn(1);
        when(repository.oldestEligible(CUTOFF)).thenThrow(new DataAccessResourceFailureException("fixture"));
        worker(COMPACT,1).cleanup();assertThat(counter("compacted")).isEqualTo(1);
        assertThat(gauge("pending")).isEqualTo(-1);assertThat(gauge("last.success.timestamp.seconds")).isEqualTo(-1);
        assertThat(registry.get("notification.resolved.dlq.retention.runs").tag("outcome","success").counter().count()).isZero();
    }
    @Test void metersUseOnlyTheTwoFixedOutcomeLabels() {
        worker(OBSERVE,1).cleanup();assertThat(registry.getMeters()).hasSize(10);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isEqualTo("outcome");assertThat(tag.getValue()).isIn("success","failed");
        }));
    }
}
