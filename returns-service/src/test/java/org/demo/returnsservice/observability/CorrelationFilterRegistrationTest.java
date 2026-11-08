package org.demo.returnsservice.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationFilterRegistrationTest {
    @Test void servicePackageRegistersExactlyOneSharedHttpFilter() throws Exception {
        try(var ctx=new AnnotationConfigApplicationContext()) {
            var scanner = new ClassPathBeanDefinitionScanner(ctx);
            // Metrics configuration has separate SQL integration coverage.
            scanner.addExcludeFilter(new AnnotationTypeFilter(Configuration.class));
            scanner.scan("org.demo.returnsservice.observability");
            ctx.refresh();
            var filters=ctx.getBeansOfType(org.demo.platformobservability.CorrelationIdFilter.class);
            assertEquals(1,filters.size());
            for(String id:new String[]{"fixture-request","bad\nvalue"}) {
                var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
                request.addHeader("X-Correlation-Id",id);
                filters.values().iterator().next().doFilter(request,response,(incoming,outgoing)->{
                    String actual=MDC.get("correlationId");assertEquals(actual,response.getHeader("X-Correlation-Id"));
                    if(id.equals("fixture-request"))assertEquals(id,actual);else assertDoesNotThrow(()->UUID.fromString(actual));
                    response.setStatus(202);response.getWriter().write("fixture");
                });
                assertEquals(202,response.getStatus());assertEquals("fixture",response.getContentAsString());
                assertNull(MDC.get("correlationId"));
            }
        } finally {MDC.clear();}
    }
}
