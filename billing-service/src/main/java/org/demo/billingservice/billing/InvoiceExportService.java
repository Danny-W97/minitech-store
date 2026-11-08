package org.demo.billingservice.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class InvoiceExportService {
    private final InvoiceExportReader reader;
    private final InvoicePdfService pdf;
    private final Semaphore slots;
    private final int pageSize, maxDocuments;
    private final long maxNanos;
    public InvoiceExportService(InvoiceExportReader reader, InvoicePdfService pdf,
            @Value("${billing.export.page-size:25}") int pageSize,
            @Value("${billing.export.max-documents:10000}") int maxDocuments,
            @Value("${billing.export.max-concurrent:2}") int concurrent,
            @Value("${billing.export.max-duration:PT5M}") Duration duration) {
        if (pageSize < 1 || pageSize > 200 || maxDocuments < 1 || maxDocuments > 10000 || concurrent < 1
                || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Invalid export limits");
        this.reader = reader; this.pdf = pdf; this.pageSize = pageSize; this.maxDocuments = maxDocuments;
        this.slots = new Semaphore(concurrent); this.maxNanos = duration.toNanos();
    }
    public Session open(Instant from, Instant to) {
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Export capacity exhausted; retry later");
        try { return new Session(reader.plan(from, to, maxDocuments)); }
        catch (RuntimeException error) { slots.release(); throw error; }
    }
    public final class Session implements AutoCloseable {
        private final InvoiceExportReader.Plan plan;
        private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
        private Session(InvoiceExportReader.Plan plan) { this.plan = plan; }
        public void writeTo(OutputStream output) throws IOException {
            if (closed.get() || !started.compareAndSet(false, true)) throw new IllegalStateException("Export session is not reusable");
            long began = System.nanoTime(), cursor = plan.ceiling(), written = 0;
            var zip = new Archive(output);
            try {
                while (cursor > 0) {
                    checkDeadline(began);
                    var page = reader.page(plan, cursor, pageSize);
                    if (page.isEmpty()) break;
                    for (var invoice : page) {
                        checkDeadline(began);
                        if (++written > maxDocuments) throw new IOException("Export document limit exceeded");
                        // Bulk reads must not fill/evict the interactive document cache.
                        byte[] document = pdf.generate(invoice);
                        checkDeadline(began);
                        String number = invoice.getInvoiceNumber() == null ? "FV" : invoice.getInvoiceNumber().replaceAll("[^A-Za-z0-9._-]", "_");
                        zip.putNextEntry(new ZipEntry(number + "_" + invoice.getId() + ".pdf"));
                        zip.write(document); zip.closeEntry(); zip.flush();
                    }
                    cursor = page.getLast().getId() - 1;
                }
                zip.finish(); zip.flush();
            } finally {
                // close() would finish a partial ZIP after failure and disguise it as a complete export.
                zip.dispose();
            }
        }
        private void checkDeadline(long began) throws IOException {
            if (System.nanoTime() - began > maxNanos || Thread.currentThread().isInterrupted())
                throw new IOException("Export deadline exceeded or export interrupted");
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) slots.release(); }
    }
    private static final class Archive extends ZipOutputStream {
        Archive(OutputStream output) { super(output); setLevel(Deflater.NO_COMPRESSION); }
        void dispose() { def.end(); }
    }
}
