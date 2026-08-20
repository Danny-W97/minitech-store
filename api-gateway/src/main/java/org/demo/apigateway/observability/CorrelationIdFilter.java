package org.demo.apigateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = CorrelationIdContext.resolve(
                request.getHeader(CorrelationIdContext.HEADER_NAME)
        );
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdContext.MDC_KEY, correlationId)) {
            response.setHeader(CorrelationIdContext.HEADER_NAME, correlationId);
            filterChain.doFilter(request, response);
        }
    }
}
