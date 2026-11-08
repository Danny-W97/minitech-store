package org.demo.platformobservability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Services opt in through their existing component, keeping filter registration/order. */
public class CorrelationIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationHeader = request.getHeader(CorrelationIdContext.HEADER_NAME);
        String traceparentHeader = request.getHeader(CorrelationIdContext.TRACEPARENT_HEADER);

        try (var scope = CorrelationIdContext.open(correlationHeader, traceparentHeader)) {
            response.setHeader(CorrelationIdContext.HEADER_NAME, CorrelationIdContext.currentOrNew());
            String traceparent = MDC.get(CorrelationIdContext.MDC_TRACEPARENT);
            if (traceparent != null) {
                response.setHeader(CorrelationIdContext.TRACEPARENT_HEADER, traceparent);
            }
            chain.doFilter(request, response);
        }
    }
}
