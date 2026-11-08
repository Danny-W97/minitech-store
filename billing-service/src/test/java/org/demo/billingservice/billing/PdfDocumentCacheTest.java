package org.demo.billingservice.billing;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.demo.billingservice.billing.PdfDocumentCache.Kind.*;

class PdfDocumentCacheTest {
    @Test void scheduledSweepReleasesExpiredCopiesWithoutAnotherDocumentRead() {
        var clock=new AtomicLong();var cache=new PdfDocumentCache(100,10,Duration.ofNanos(10),clock::get);
        cache.put(INVOICE,1L,new byte[]{1,2,3});clock.set(10);cache.expire();
        assertThat((java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(cache,"entries")).isEmpty();
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(cache,"bytes")).isEqualTo(0L);
    }
    @Test void bothKindsShareOneByteBudgetAndEvictLeastRecentlyUsed() {
        var cache = new PdfDocumentCache(10, 10, Duration.ofMinutes(1), () -> 0);
        cache.put(INVOICE, 1L, new byte[4]); cache.put(CORRECTION, 1L, new byte[4]);
        assertThat(cache.get(INVOICE, 1L)).hasSize(4);
        cache.put(INVOICE, 2L, new byte[4]);
        assertThat(cache.get(CORRECTION, 1L)).isNull();
        assertThat(cache.get(INVOICE, 1L)).hasSize(4);
        assertThat(cache.bytes()).isEqualTo(8);
        cache.put(INVOICE, 3L, new byte[11]);
        assertThat(cache.bytes()).isEqualTo(8);
        assertThat(cache.get(INVOICE, 3L)).isNull();
    }
    @Test void expiresFromWriteTimeAndProtectsStoredBytes() {
        var clock = new AtomicLong();
        var cache = new PdfDocumentCache(100, 10, Duration.ofNanos(10), clock::get);
        var source = new byte[]{1,2}; cache.put(INVOICE, 1L, source); source[0] = 9;
        var read = cache.get(INVOICE, 1L); assertThat(read[0]).isEqualTo((byte)1); read[0] = 7;
        clock.set(9); assertThat(cache.get(INVOICE, 1L)[0]).isEqualTo((byte)1);
        clock.set(10); assertThat(cache.get(INVOICE, 1L)).isNull(); assertThat(cache.bytes()).isZero();
    }
    @Test void entryLimitAlsoBoundsEmptyDocumentsAndZeroBudgetDisablesCaching() {
        var cache = new PdfDocumentCache(100, 2, Duration.ofMinutes(1), () -> 0);
        for (long i=0; i<100; i++) cache.put(INVOICE, i, new byte[0]);
        assertThat(cache.size()).isEqualTo(2);
        cache.clear(); assertThat(cache.bytes()).isZero(); assertThat(cache.size()).isZero();
        var disabled = new PdfDocumentCache(0, 2, Duration.ofMinutes(1), () -> 0);
        disabled.put(INVOICE, 1L, new byte[0]); assertThat(disabled.size()).isZero();
    }
    @Test void concurrentReadsAndWritesNeverExceedTheSharedBudget() throws Exception {
        var cache = new PdfDocumentCache(1024, 20, Duration.ofMinutes(1), System::nanoTime);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.LongStream.range(0, 1000).mapToObj(id -> workers.submit(() -> {
                cache.put(id%2 == 0 ? INVOICE : CORRECTION, id, new byte[100]);
                cache.get(INVOICE, id); assertThat(cache.bytes()).isLessThanOrEqualTo(1024);
            })).toList();
            for (var future : futures) future.get();
        }
        assertThat(cache.size()).isLessThanOrEqualTo(10);
    }
}
