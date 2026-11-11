package org.demo.platformobservability;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared wire/log contract; correlation and trace IDs never identify an authenticated actor. */
public final class CorrelationIdContext {
    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String MDC_KEY = "correlationId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TRACEPARENT = "traceparent";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile("^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    public record TraceMetadata(String traceId, String spanId, String flags, String traceparent) {}

    private CorrelationIdContext() {}

    public static String resolve(String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        return SAFE_ID.matcher(value).matches() ? value : UUID.randomUUID().toString();
    }

    public static String currentOrNew() {
        String correlationId = resolve(MDC.get(MDC_KEY));
        MDC.put(MDC_KEY, correlationId);
        return correlationId;
    }

    public static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        if (allZeros(bytes)) bytes[0] = 1;
        return HEX.formatHex(bytes);
    }

    private static boolean allZeros(byte[] bytes) {
        for (byte b : bytes) if (b != 0) return false;
        return true;
    }

    public static TraceMetadata resolveTrace(String traceparentCandidate) {
        if (traceparentCandidate != null) {
            String trimmed = traceparentCandidate.trim();
            Matcher matcher = TRACEPARENT_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                String traceId = matcher.group(1).toLowerCase();
                String spanId = matcher.group(2).toLowerCase();
                String flags = matcher.group(3).toLowerCase();
                if (!traceId.equals("0".repeat(32)) && !spanId.equals("0".repeat(16))) {
                    return new TraceMetadata(traceId, spanId, flags, "00-" + traceId + "-" + spanId + "-" + flags);
                }
            }
        }
        String traceId = randomHex(16);
        String spanId = randomHex(8);
        String flags = "01";
        return new TraceMetadata(traceId, spanId, flags, "00-" + traceId + "-" + spanId + "-" + flags);
    }

    public static String childTraceparent() {
        String currentTraceId = MDC.get(MDC_TRACE_ID);
        String currentParent = MDC.get(MDC_TRACEPARENT);
        String flags = "01";
        if (currentParent != null) {
            Matcher m = TRACEPARENT_PATTERN.matcher(currentParent);
            if (m.matches()) flags = m.group(3);
        }
        String traceId = (currentTraceId != null && currentTraceId.length() == 32 && !currentTraceId.equals("0".repeat(32)))
                ? currentTraceId
                : randomHex(16);
        String childSpanId = randomHex(8);
        return "00-" + traceId + "-" + childSpanId + "-" + flags;
    }

    /** A new message never inherits the previous message's ID when its own header is absent. */
    public static Scope open(String candidate) { return open(candidate, childTraceparent()); }

    public static Scope open(String correlationCandidate, String traceparentCandidate) {
        String correlationId = resolve(correlationCandidate);
        TraceMetadata trace = resolveTrace(traceparentCandidate);
        return new Scope(correlationId, trace);
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Scope parent = ACTIVE.get();
        private final String prevCorrelation;
        private final String prevTraceId;
        private final String prevSpanId;
        private final String prevTraceparent;
        private boolean closed;

        private Scope(String id, TraceMetadata trace) {
            String current = MDC.get(MDC_KEY);
            prevCorrelation = current != null && SAFE_ID.matcher(current).matches() ? current : null;
            prevTraceId = MDC.get(MDC_TRACE_ID);
            prevSpanId = MDC.get(MDC_SPAN_ID);
            prevTraceparent = MDC.get(MDC_TRACEPARENT);

            ACTIVE.set(this);
            MDC.put(MDC_KEY, id);
            if (trace != null) {
                MDC.put(MDC_TRACE_ID, trace.traceId());
                MDC.put(MDC_SPAN_ID, trace.spanId());
                MDC.put(MDC_TRACEPARENT, trace.traceparent());
            }
        }

        @Override public void close() {
            if (closed) return;
            if (owner != Thread.currentThread() || ACTIVE.get() != this)
                throw new IllegalStateException("Correlation scope must close on its owner thread in reverse order");

            restoreOrRemove(MDC_KEY, prevCorrelation);
            restoreOrRemove(MDC_TRACE_ID, prevTraceId);
            restoreOrRemove(MDC_SPAN_ID, prevSpanId);
            restoreOrRemove(MDC_TRACEPARENT, prevTraceparent);

            if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent);
            closed = true;
        }

        private static void restoreOrRemove(String key, String prev) {
            if (prev == null) MDC.remove(key); else MDC.put(key, prev);
        }
    }
}
