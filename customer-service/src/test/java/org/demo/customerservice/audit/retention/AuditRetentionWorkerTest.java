package org.demo.customerservice.audit.retention;

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
import static org.demo.customerservice.audit.retention.AuditRetentionProperties.Mode.*;

class AuditRetentionWorkerTest {
    static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z"), CUTOFF=NOW.minus(Duration.ofDays(90));
    private final AuditRetentionRepository repository=mock(AuditRetentionRepository.class);
    private final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    private AuditRetentionWorker activeWorker;
    static AuditRetentionProperties properties(AuditRetentionProperties.Mode mode,int maxBatches) {
        return new AuditRetentionProperties(mode,Duration.ofDays(90),2,maxBatches,600000,60000);
    }
    private AuditRetentionWorker worker(AuditRetentionProperties.Mode mode,int maxBatches) {
        activeWorker=new AuditRetentionWorker(repository,properties(mode,maxBatches),registry,Clock.fixed(NOW,ZoneOffset.UTC));return activeWorker;
    }
    private double gauge(String name) {return registry.get("customer.audit.retention."+name).gauge().value();}
    private double counter(String name) {return registry.get("customer.audit.retention."+name).counter().count();}

    @Test void offDoesNotAccessTheDatabaseOrPretendUnknownDataIsEmpty() {
        worker(OFF,4).cleanup();verifyNoInteractions(repository);
        assertThat(gauge("enabled")).isZero();assertThat(gauge("purge.enabled")).isZero();
        assertThat(gauge("pending")).isEqualTo(-1);assertThat(gauge("last.success.timestamp.seconds")).isEqualTo(-1);
    }
    @Test void observeReportsOldDataWithoutIssuingDeleteStatements() {
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(5));
        worker(OBSERVE,4).cleanup();verify(repository).oldestEligible(CUTOFF);verifyNoMoreInteractions(repository);
        assertThat(gauge("purge.enabled")).isZero();assertThat(gauge("enabled")).isEqualTo(1);
        assertThat(gauge("pending")).isEqualTo(1);assertThat(gauge("oldest.eligible.age.seconds")).isEqualTo(7776005);
        assertThat(gauge("last.deleted")).isZero();assertThat(counter("deleted")).isZero();
    }
    @Test void purgeUsesOneCutoffAndStopsAtItsBatchBudget() {
        when(repository.deleteBatch(CUTOFF,2)).thenReturn(2);
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(1));
        worker(PURGE,3).cleanup();verify(repository,times(3)).deleteBatch(CUTOFF,2);
        assertThat(counter("deleted")).isEqualTo(6);assertThat(gauge("last.deleted")).isEqualTo(6);
        assertThat(gauge("purge.enabled")).isEqualTo(1);assertThat(gauge("pending")).isEqualTo(1);
    }
    @Test void aShortOrLockedBatchStopsButDoesNotHideTheEligibleBacklog() {
        when(repository.deleteBatch(CUTOFF,2)).thenReturn(0);
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(1));
        worker(PURGE,4).cleanup();verify(repository).deleteBatch(CUTOFF,2);
        assertThat(gauge("pending")).isEqualTo(1);assertThat(gauge("last.deleted")).isZero();
    }
    @Test void laterTransactionFailurePreservesCommittedCountsAndPreviousSnapshotThenRecovers() {
        when(repository.deleteBatch(CUTOFF,2)).thenReturn(0,2).thenThrow(new CannotCreateTransactionException("fixture")).thenReturn(0);
        when(repository.oldestEligible(CUTOFF)).thenReturn(CUTOFF.minusSeconds(5),null);
        var worker=worker(PURGE,4);worker.cleanup();worker.cleanup();
        assertThat(counter("deleted")).isEqualTo(2);assertThat(gauge("pending")).isEqualTo(1);
        assertThat(gauge("last.deleted")).isZero();assertThat(gauge("last.success.timestamp.seconds")).isEqualTo(NOW.getEpochSecond());
        assertThat(registry.get("customer.audit.retention.runs").tag("outcome","failed").counter().count()).isEqualTo(1);
        worker.cleanup();assertThat(gauge("pending")).isZero();assertThat(counter("deleted")).isEqualTo(2);
    }
    @Test void snapshotFailureCannotAnnounceSuccessOrEraseCommittedDeletions() {
        when(repository.deleteBatch(CUTOFF,2)).thenReturn(1);
        when(repository.oldestEligible(CUTOFF)).thenThrow(new DataAccessResourceFailureException("fixture"));
        worker(PURGE,1).cleanup();assertThat(counter("deleted")).isEqualTo(1);
        assertThat(gauge("pending")).isEqualTo(-1);assertThat(gauge("last.success.timestamp.seconds")).isEqualTo(-1);
        assertThat(registry.get("customer.audit.retention.runs").tag("outcome","success").counter().count()).isZero();
    }
    @Test void metersUseOnlyTheTwoFixedOutcomeLabels() {
        worker(OBSERVE,1).cleanup();assertThat(registry.getMeters()).hasSize(10);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isEqualTo("outcome");assertThat(tag.getValue()).isIn("success","failed");
        }));
    }
}
