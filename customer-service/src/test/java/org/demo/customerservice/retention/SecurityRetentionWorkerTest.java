package org.demo.customerservice.retention;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.demo.customerservice.retention.SecurityRetentionRepository.Store.*;

class SecurityRetentionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));
    private final SecurityRetentionRepository repository = mock(SecurityRetentionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private SecurityRetentionWorker activeWorker; // Match the lifecycle of Spring-owned weak gauge references.
    static SecurityRetentionProperties properties(boolean enabled, int maxBatches) {
        return new SecurityRetentionProperties(enabled, Duration.ofDays(7), 2, maxBatches, 600000, 60000);
    }
    private SecurityRetentionWorker worker(boolean enabled, int maxBatches) {
        activeWorker = new SecurityRetentionWorker(repository, properties(enabled,maxBatches), registry, Clock.fixed(NOW, ZoneOffset.UTC));
        return activeWorker;
    }
    private double gauge(String name, SecurityRetentionRepository.Store store) {
        return registry.get("customer.security.retention."+name).tag("store",store.metricTag()).gauge().value();
    }
    @Test void disabledWorkerDoesNotPretendUnknownDataIsEmpty() {
        worker(false,2).cleanup(); verifyNoInteractions(repository);
        assertThat(gauge("pending",REFRESH)).isEqualTo(-1);
        assertThat(gauge("last.success.timestamp.seconds",REFRESH)).isEqualTo(-1);
    }
    @Test void oneCutoffAndIndependentBatchBudgetsApplyToEveryStore() {
        when(repository.cleanupBatch(REFRESH,CUTOFF,2)).thenReturn(2,1);
        when(repository.cleanupBatch(PASSWORD_RESET,CUTOFF,2)).thenReturn(2);
        when(repository.oldestEligible(PASSWORD_RESET,CUTOFF)).thenReturn(CUTOFF.minusSeconds(1));
        worker(true,2).cleanup();
        verify(repository,times(2)).cleanupBatch(REFRESH,CUTOFF,2);
        verify(repository,times(2)).cleanupBatch(PASSWORD_RESET,CUTOFF,2);
        verify(repository).cleanupBatch(RECOVERY_USED,CUTOFF,2);
        verify(repository).cleanupBatch(TOTP_INACTIVE,CUTOFF,2);
        verify(repository).cleanupBatch(VERIFICATION_EXPIRED,CUTOFF,2);
        verify(repository).cleanupBatch(VERIFICATION_CONFIRMED,CUTOFF,2);
        assertThat(gauge("last.deleted",REFRESH)).isEqualTo(3);
        assertThat(gauge("pending",PASSWORD_RESET)).isEqualTo(1);
        assertThat(gauge("oldest.eligible.age.seconds",PASSWORD_RESET)).isEqualTo(604801);
    }
    @Test void lockedEligibleDataRemainsVisibleEvenWhenNoRowsWereRemoved() {
        when(repository.oldestEligible(TOTP_INACTIVE,CUTOFF)).thenReturn(CUTOFF.minusSeconds(5));
        worker(true,4).cleanup();
        assertThat(gauge("last.deleted",TOTP_INACTIVE)).isZero();
        assertThat(gauge("pending",TOTP_INACTIVE)).isEqualTo(1);
    }
    @Test void oneFailureDoesNotStopOtherStoresOrEraseCommittedDeletionCounters() {
        when(repository.cleanupBatch(REFRESH,CUTOFF,2)).thenReturn(2).thenThrow(new CannotCreateTransactionException("fixture"));
        worker(true,4).cleanup();
        verify(repository,never()).oldestEligible(REFRESH,CUTOFF);
        verify(repository).cleanupBatch(TOTP_INACTIVE,CUTOFF,2);
        assertThat(registry.get("customer.security.retention.deleted").tag("store","refresh").counter().count()).isEqualTo(2);
        assertThat(gauge("last.success.timestamp.seconds",REFRESH)).isEqualTo(-1);
        assertThat(gauge("last.success.timestamp.seconds",TOTP_INACTIVE)).isEqualTo(NOW.getEpochSecond());
    }
    @Test void snapshotFailureCannotAnnounceSuccessfulCleanup() {
        when(repository.oldestEligible(REFRESH,CUTOFF)).thenThrow(new DataAccessResourceFailureException("fixture"));
        worker(true,1).cleanup();
        assertThat(gauge("pending",REFRESH)).isEqualTo(-1);
        assertThat(registry.get("customer.security.retention.runs").tag("store","refresh").tag("outcome","failed").counter().count()).isEqualTo(1);
    }
    @Test void aSubsequentRunRecoversTheState() {
        when(repository.cleanupBatch(REFRESH,CUTOFF,2)).thenThrow(new DataAccessResourceFailureException("fixture")).thenReturn(0);
        var worker=worker(true,1);worker.cleanup();worker.cleanup();
        assertThat(gauge("pending",REFRESH)).isZero();
        assertThat(gauge("last.success.timestamp.seconds",REFRESH)).isEqualTo(NOW.getEpochSecond());
    }
    @Test void metricSeriesHaveOnlyFixedStoreAndOutcomeLabels() {
        worker(true,1).cleanup();
        assertThat(registry.getMeters()).hasSize(44);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("store","outcome")));
    }

    @Test void verificationCleanupUsesSeparateCountersWithoutReportingDeletedAccounts() {
        when(repository.cleanupBatch(VERIFICATION_EXPIRED,CUTOFF,2)).thenReturn(2);
        when(repository.cleanupBatch(VERIFICATION_CONFIRMED,CUTOFF,2)).thenReturn(1);
        when(repository.oldestEligible(VERIFICATION_EXPIRED,CUTOFF)).thenReturn(CUTOFF.minusSeconds(10));
        worker(true,1).cleanup();
        assertThat(gauge("last.cleared",VERIFICATION_EXPIRED)).isEqualTo(2);
        assertThat(gauge("last.cleared",VERIFICATION_CONFIRMED)).isEqualTo(1);
        assertThat(gauge("pending",VERIFICATION_EXPIRED)).isEqualTo(1);
        assertThat(registry.get("customer.security.retention.cleared").tag("store","verification_expired").counter().count()).isEqualTo(2);
        assertThat(registry.find("customer.security.retention.deleted").tag("store","verification_expired").counter()).isNull();
    }

    @Test void verificationFailureDoesNotHideTheNextCategoryOrProduceACleanSnapshot() {
        when(repository.cleanupBatch(VERIFICATION_EXPIRED,CUTOFF,2)).thenThrow(new CannotCreateTransactionException("fixture"));
        worker(true,1).cleanup();
        assertThat(gauge("pending",VERIFICATION_EXPIRED)).isEqualTo(-1);
        assertThat(gauge("last.cleared",VERIFICATION_EXPIRED)).isEqualTo(-1);
        assertThat(gauge("pending",VERIFICATION_CONFIRMED)).isZero();
        assertThat(registry.get("customer.security.retention.runs").tag("store","verification_expired").tag("outcome","failed").counter().count()).isEqualTo(1);
    }
}
