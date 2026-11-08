package org.demo.billingservice.messaging.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.billingservice.messaging.retention.InvoiceStreamRetentionProperties.Mode.*;

class InvoiceStreamRetentionWorkerTest {
    static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");static final long CUTOFF=NOW.minus(Duration.ofDays(30)).toEpochMilli();
    private final InvoiceStreamRetentionRepository repository=mock(InvoiceStreamRetentionRepository.class);
    private final SimpleMeterRegistry registry=new SimpleMeterRegistry();private InvoiceStreamRetentionWorker owner;
    static InvoiceStreamRetentionProperties properties(InvoiceStreamRetentionProperties.Mode mode,int batches) {
        return new InvoiceStreamRetentionProperties(mode,Duration.ofDays(30),2,batches,600000,60000,"",true);
    }
    private InvoiceStreamRetentionWorker worker(InvoiceStreamRetentionProperties.Mode mode,int batches) {
        owner=new InvoiceStreamRetentionWorker(repository,properties(mode,batches),registry,Clock.fixed(NOW,ZoneOffset.UTC));return owner;
    }
    private InvoiceStreamRetentionRepository.Snapshot snapshot(long removed,boolean eligible,int blocked) {
        return new InvoiceStreamRetentionRepository.Snapshot(removed,10,"1-0",eligible,blocked,0,true);
    }
    private double gauge(String key){return registry.get("billing.invoice.stream.retention."+key).gauge().value();}
    private double deleted(){return registry.get("billing.invoice.stream.retention.deleted").counter().count();}
    @Test void offNeverContactsRedisAndKeepsUnknownSnapshot() {
        worker(OFF,4).cleanup();verifyNoInteractions(repository);assertThat(gauge("length")).isEqualTo(-1);assertThat(gauge("enabled")).isZero();
    }
    @Test void observeUsesOneReadOnlySnapshotAndExposesOldVersusEligible() {
        when(repository.run(CUTOFF,false,2,List.of(),true)).thenReturn(snapshot(0,false,5));worker(OBSERVE,4).cleanup();
        verify(repository).run(CUTOFF,false,2,List.of(),true);verifyNoMoreInteractions(repository);
        assertThat(gauge("pending")).isEqualTo(1);assertThat(gauge("eligible")).isZero();assertThat(gauge("blocked.code")).isEqualTo(5);
        assertThat(gauge("deletion.enabled")).isZero();assertThat(deleted()).isZero();
    }
    @Test void trimUsesOneCutoffAndHonorsItsBatchBudget() {
        when(repository.run(CUTOFF,true,2,List.of(),true)).thenReturn(snapshot(2,true,0));worker(TRIM,3).cleanup();
        verify(repository,times(3)).run(CUTOFF,true,2,List.of(),true);assertThat(deleted()).isEqualTo(6);assertThat(gauge("last.deleted")).isEqualTo(6);
    }
    @Test void blockedOrDrainedSnapshotStopsWithoutExtraCalls() {
        when(repository.run(CUTOFF,true,2,List.of(),true)).thenReturn(snapshot(2,false,5));worker(TRIM,4).cleanup();
        verify(repository).run(CUTOFF,true,2,List.of(),true);assertThat(deleted()).isEqualTo(2);assertThat(gauge("blocked.code")).isEqualTo(5);
    }
    @Test void laterFailureKeepsAcknowledgedDeletesAndPreviousSnapshotThenRecovers() {
        when(repository.run(CUTOFF,true,2,List.of(),true)).thenReturn(snapshot(0,true,0),snapshot(2,true,0))
                .thenThrow(new DataAccessResourceFailureException("fixture sensitive detail")).thenReturn(snapshot(0,false,0));
        var worker=worker(TRIM,4);worker.cleanup();worker.cleanup();assertThat(deleted()).isEqualTo(2);assertThat(gauge("last.deleted")).isZero();
        assertThat(gauge("eligible")).isEqualTo(1);assertThat(registry.get("billing.invoice.stream.retention.runs").tag("outcome","failed").counter().count()).isEqualTo(1);
        worker.cleanup();assertThat(gauge("eligible")).isZero();assertThat(deleted()).isEqualTo(2);
    }
    @Test void metricsUseOnlyFixedOutcomeLabels() {
        worker(OBSERVE,1);assertThat(registry.getMeters()).hasSize(14);
        assertThat(registry.getMeters()).allSatisfy(m->assertThat(m.getId().getTags()).allSatisfy(t->{assertThat(t.getKey()).isEqualTo("outcome");assertThat(t.getValue()).isIn("success","failed");}));
    }
}
