package org.demo.recommendationservice.observability;

import java.util.UUID;

public final class CorrelationIdContext {
    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationIdContext() {}

    public static String resolve(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
