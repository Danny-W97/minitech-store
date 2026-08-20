package org.demo.paymentservice.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIdContext {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");

    private CorrelationIdContext() {
    }

    public static String resolve(String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        return SAFE_ID.matcher(value).matches() ? value : UUID.randomUUID().toString();
    }

    public static String currentOrNew() {
        String correlationId = resolve(MDC.get(MDC_KEY));
        MDC.put(MDC_KEY, correlationId);
        return correlationId;
    }
}
