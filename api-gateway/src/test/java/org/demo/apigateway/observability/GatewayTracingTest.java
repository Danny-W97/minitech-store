package org.demo.apigateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.demo.platformobservability.CorrelationIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class GatewayTracingTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void propagatesValidIncomingTraceparentAndCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        String incomingTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        request.addHeader("traceparent", incomingTraceparent);
        request.addHeader("X-Correlation-Id", "corr-req-7788");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcTraceId = new AtomicReference<>();
        AtomicReference<String> mdcSpanId = new AtomicReference<>();
        AtomicReference<String> mdcTraceparent = new AtomicReference<>();
        AtomicReference<String> mdcCorrelationId = new AtomicReference<>();
        AtomicReference<String> childTraceparent = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            mdcTraceId.set(MDC.get(CorrelationIdContext.MDC_TRACE_ID));
            mdcSpanId.set(MDC.get(CorrelationIdContext.MDC_SPAN_ID));
            mdcTraceparent.set(MDC.get(CorrelationIdContext.MDC_TRACEPARENT));
            mdcCorrelationId.set(MDC.get(CorrelationIdContext.MDC_KEY));
            childTraceparent.set(CorrelationIdContext.childTraceparent());
        };

        filter.doFilter(request, response, chain);

        assertThat(mdcTraceId.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(mdcSpanId.get()).isEqualTo("00f067aa0ba902b7");
        assertThat(mdcTraceparent.get()).isEqualTo(incomingTraceparent);
        assertThat(mdcCorrelationId.get()).isEqualTo("corr-req-7788");

        // Response receives traceparent and X-Correlation-Id
        assertThat(response.getHeader(CorrelationIdContext.TRACEPARENT_HEADER)).isEqualTo(incomingTraceparent);
        assertThat(response.getHeader(CorrelationIdContext.HEADER_NAME)).isEqualTo("corr-req-7788");

        // Child traceparent retains same traceId, has new 16-hex spanId, flags 01
        assertThat(childTraceparent.get()).isNotNull();
        String[] parts = childTraceparent.get().split("-");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("00");
        assertThat(parts[1]).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(parts[2]).hasSize(16);
        assertThat(parts[2]).isNotEqualTo("00f067aa0ba902b7");
        assertThat(parts[3]).isEqualTo("01");

        // MDC cleaned up after filter execution
        assertThat(MDC.get(CorrelationIdContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(CorrelationIdContext.MDC_TRACEPARENT)).isNull();
    }

    @Test
    void generatesFreshTraceparentWhenIncomingRequestLacksHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcTraceId = new AtomicReference<>();
        AtomicReference<String> mdcTraceparent = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            mdcTraceId.set(MDC.get(CorrelationIdContext.MDC_TRACE_ID));
            mdcTraceparent.set(MDC.get(CorrelationIdContext.MDC_TRACEPARENT));
        };

        filter.doFilter(request, response, chain);

        assertThat(mdcTraceId.get()).isNotNull().hasSize(32);
        assertThat(mdcTraceparent.get()).isNotNull();
        assertThat(response.getHeader(CorrelationIdContext.TRACEPARENT_HEADER)).isEqualTo(mdcTraceparent.get());
        String correlationHeader = response.getHeader(CorrelationIdContext.HEADER_NAME);
        assertThat(correlationHeader).isNotNull();
        assertDoesNotThrow(() -> UUID.fromString(correlationHeader));
    }
}
