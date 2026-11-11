package org.demo.customerservice.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CorrelationIdFilter extends org.demo.platformobservability.CorrelationIdFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try (var ignoredIp = MDC.putCloseable("auditClientIp", request.getRemoteAddr());
             var ignoredAgent = MDC.putCloseable("auditUserAgent",
                     request.getHeader("User-Agent") == null ? "" : request.getHeader("User-Agent"))) {
            super.doFilterInternal(request, response, chain);
        }
    }
}
