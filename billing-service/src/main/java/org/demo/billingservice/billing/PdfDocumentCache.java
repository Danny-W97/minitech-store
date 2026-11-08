package org.demo.billingservice.billing;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** One byte budget for both document types, with LRU eviction and expire-after-write. */
@Component
public class PdfDocumentCache {
    public enum Kind { INVOICE, CORRECTION }
    private record Key(Kind kind, Long id) {}
    private record Entry(byte[] bytes, long writtenAt) {}
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final long maxBytes, ttlNanos;
    private final int maxEntries;
    private final LongSupplier clock;
    private long bytes;

    @Autowired
    public PdfDocumentCache(@Value("${billing.pdf.cache.max-bytes:33554432}") long maxBytes,
            @Value("${billing.pdf.cache.max-entries:512}") int maxEntries,
            @Value("${billing.pdf.cache.ttl:PT15M}") Duration ttl, MeterRegistry metrics) {
        this(maxBytes, maxEntries, ttl, System::nanoTime);
        metrics.gauge("billing.pdf.cache.bytes", this, PdfDocumentCache::bytes);
        metrics.gauge("billing.pdf.cache.entries", this, PdfDocumentCache::size);
    }

    PdfDocumentCache(long maxBytes, int maxEntries, Duration ttl, LongSupplier clock) {
        if (maxBytes < 0 || maxEntries < 0 || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("PDF cache budgets must be nonnegative and TTL positive");
        this.maxBytes = maxBytes; this.maxEntries = maxEntries; this.ttlNanos = ttl.toNanos(); this.clock = clock;
    }
    public synchronized byte[] get(Kind kind, Long id) {
        expire();
        var entry = entries.get(new Key(kind, id));
        return entry == null ? null : entry.bytes().clone();
    }
    public synchronized void put(Kind kind, Long id, byte[] value) {
        expire();
        var key = new Key(kind, id);
        var previous = entries.remove(key);
        if (previous != null) bytes -= previous.bytes().length;
        if (value.length > maxBytes || maxBytes == 0 || maxEntries == 0) return;
        while (bytes > maxBytes - value.length || entries.size() >= maxEntries) {
            var oldest = entries.pollFirstEntry();
            bytes -= oldest.getValue().bytes().length;
        }
        entries.put(key, new Entry(value.clone(), clock.getAsLong())); bytes += value.length;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${billing.pdf.cache.sweep-ms:60000}")
    public synchronized void expire() {
        long now = clock.getAsLong();
        entries.entrySet().removeIf(entry -> {
            if (now - entry.getValue().writtenAt() < ttlNanos) return false;
            bytes -= entry.getValue().bytes().length; return true;
        });
    }
    public synchronized long bytes() { expire(); return bytes; }
    public synchronized int size() { expire(); return entries.size(); }
    public synchronized void clear() { entries.clear(); bytes = 0; }
}
