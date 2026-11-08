package org.demo.customerservice.observability;

import jakarta.servlet.ServletException;
import org.demo.platformobservability.CorrelationIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationAuditTest {
    @AfterEach void clear() { MDC.clear(); }
    @Test void customerAuditMetadataWrapsTheSharedCorrelationFilter() throws Exception {
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.42");
        request.addHeader("User-Agent","audit-fixture");request.addHeader(CorrelationIdContext.HEADER_NAME,"customer-audit");
        new CorrelationIdFilter().doFilter(request,new MockHttpServletResponse(),(incoming,outgoing)->{
            assertEquals("customer-audit",MDC.get(CorrelationIdContext.MDC_KEY));
            assertEquals("192.0.2.42",MDC.get("auditClientIp"));assertEquals("audit-fixture",MDC.get("auditUserAgent"));
        });
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));assertNull(MDC.get("auditClientIp"));assertNull(MDC.get("auditUserAgent"));
    }
    @Test void auditMetadataIsClearedOnFailureAndMissingAgentStillWorks() {
        assertThrows(ServletException.class,()->new CorrelationIdFilter().doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),
                (incoming,outgoing)->{assertEquals("",MDC.get("auditUserAgent"));throw new ServletException("fixture");}));
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));assertNull(MDC.get("auditClientIp"));assertNull(MDC.get("auditUserAgent"));
    }
}
