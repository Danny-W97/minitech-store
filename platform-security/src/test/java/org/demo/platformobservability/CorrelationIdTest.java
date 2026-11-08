package org.demo.platformobservability;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdTest {
    @AfterEach void clear() { MDC.clear(); }

    @ParameterizedTest
    @ValueSource(strings={"order-123", "trace:service.v1_2", "a", " Z9 ", "1234"})
    void safeIdsArePreservedAndTrimmed(String candidate) {
        assertEquals(candidate.trim(), CorrelationIdContext.resolve(candidate));
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ", "line\nforged-log", "id\r\nX-Other: bad", "<script>", "ą-id", ":starts-with-punctuation"})
    void untrustedIdsAreReplacedWithUuids(String candidate) {
        String resolved=CorrelationIdContext.resolve(candidate);
        assertDoesNotThrow(()->UUID.fromString(resolved));
        assertNotEquals(candidate, resolved);
    }

    @Test void exactlyOneHundredAsciiCharactersAreAllowed() {
        assertEquals("a".repeat(100), CorrelationIdContext.resolve("a".repeat(100)));
        assertDoesNotThrow(()->UUID.fromString(CorrelationIdContext.resolve("a".repeat(101))));
    }

    @Test void outgoingCallsReuseOneSanitizedMdcValue() throws Exception {
        MDC.put(CorrelationIdContext.MDC_KEY, "unsafe\nvalue");
        var interceptor=new CorrelationIdClientInterceptor();
        String previous=null;
        for(int attempt=0;attempt<2;attempt++) {
            var request=new MockClientHttpRequest();
            request.getHeaders().set(CorrelationIdContext.HEADER_NAME, "caller-override");
            byte[] body={1,2,3};
            try(var response=interceptor.intercept(request, body, (outgoing, actualBody)->{
                assertArrayEquals(body, actualBody);
                assertEquals(MDC.get(CorrelationIdContext.MDC_KEY), outgoing.getHeaders().getFirst(CorrelationIdContext.HEADER_NAME));
                assertEquals(1, outgoing.getHeaders().get(CorrelationIdContext.HEADER_NAME).size());
                return new MockClientHttpResponse(new byte[0], HttpStatus.ACCEPTED);
            })) { assertEquals(HttpStatus.ACCEPTED, response.getStatusCode()); }
            String current=request.getHeaders().getFirst(CorrelationIdContext.HEADER_NAME);
            assertDoesNotThrow(()->UUID.fromString(current));
            if(previous!=null) assertEquals(previous,current);
            previous=current;
        }
    }

    @Test void reusedServletThreadDoesNotCarryThePreviousRequestId() throws Exception {
        var filter=new CorrelationIdFilter();
        for(String id:new String[]{"request-one","request-two"}) {
            var request=new MockHttpServletRequest();request.addHeader(CorrelationIdContext.HEADER_NAME,id);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(incoming,outgoing)->{
                assertEquals(id,MDC.get(CorrelationIdContext.MDC_KEY));
                assertEquals(id,response.getHeader(CorrelationIdContext.HEADER_NAME));
                response.setStatus(202);response.getWriter().write("saved");
            });
            assertEquals(202,response.getStatus());assertEquals("saved",response.getContentAsString());
            assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
        }
    }

    @Test void servletAndIoFailuresClearRequestContext() {
        var filter=new CorrelationIdFilter();
        assertThrows(ServletException.class,()->filter.doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),
                (request,response)->{assertNotNull(MDC.get(CorrelationIdContext.MDC_KEY));throw new ServletException("fixture");}));
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
        assertThrows(IOException.class,()->filter.doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),
                (request,response)->{throw new IOException("fixture");}));
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
    }

    @Test void responseHeaderAndMdcUseTheSameReplacementForAnInvalidHeader() throws Exception {
        var request=new MockHttpServletRequest();request.addHeader(CorrelationIdContext.HEADER_NAME,"a".repeat(101));
        var response=new MockHttpServletResponse();
        new CorrelationIdFilter().doFilter(request,response,(incoming,outgoing)->{
            String id=response.getHeader(CorrelationIdContext.HEADER_NAME);
            assertDoesNotThrow(()->UUID.fromString(id));assertEquals(id,MDC.get(CorrelationIdContext.MDC_KEY));
        });
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
    }

    @Test void validW3cTraceparentIsParsedAndExportedToMdcAndResponse() throws Exception {
        var request = new MockHttpServletRequest();
        String validTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        request.addHeader(CorrelationIdContext.TRACEPARENT_HEADER, validTraceparent);
        var response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, (incoming, outgoing) -> {
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MDC.get(CorrelationIdContext.MDC_TRACE_ID));
            assertEquals("00f067aa0ba902b7", MDC.get(CorrelationIdContext.MDC_SPAN_ID));
            assertEquals(validTraceparent, MDC.get(CorrelationIdContext.MDC_TRACEPARENT));
            assertEquals(validTraceparent, response.getHeader(CorrelationIdContext.TRACEPARENT_HEADER));
        });

        assertNull(MDC.get(CorrelationIdContext.MDC_TRACE_ID));
        assertNull(MDC.get(CorrelationIdContext.MDC_SPAN_ID));
        assertNull(MDC.get(CorrelationIdContext.MDC_TRACEPARENT));
    }

    @Test void missingTraceparentGeneratesFreshW3cCompliantHeader() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, (incoming, outgoing) -> {
            String traceId = MDC.get(CorrelationIdContext.MDC_TRACE_ID);
            String spanId = MDC.get(CorrelationIdContext.MDC_SPAN_ID);
            assertNotNull(traceId);
            assertEquals(32, traceId.length());
            assertNotNull(spanId);
            assertEquals(16, spanId.length());
            String expected = "00-" + traceId + "-" + spanId + "-01";
            assertEquals(expected, response.getHeader(CorrelationIdContext.TRACEPARENT_HEADER));
        });

        assertNull(MDC.get(CorrelationIdContext.MDC_TRACE_ID));
        assertNull(MDC.get(CorrelationIdContext.MDC_SPAN_ID));
    }

    @Test void invalidTraceparentIsReplacedWithFreshTrace() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdContext.TRACEPARENT_HEADER, "00-00000000000000000000000000000000-0000000000000000-01");
        var response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, (incoming, outgoing) -> {
            String traceId = MDC.get(CorrelationIdContext.MDC_TRACE_ID);
            assertNotEquals("00000000000000000000000000000000", traceId);
            assertEquals(32, traceId.length());
        });
    }

    @Test void outgoingCallsPropagateChildTraceparentAndSameTraceId() throws Exception {
        String parentTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        MDC.put(CorrelationIdContext.MDC_TRACE_ID, parentTraceId);
        MDC.put(CorrelationIdContext.MDC_TRACEPARENT, "00-" + parentTraceId + "-00f067aa0ba902b7-01");

        var interceptor = new CorrelationIdClientInterceptor();
        var request = new MockClientHttpRequest();
        byte[] body = new byte[0];

        try (var response = interceptor.intercept(request, body, (outgoing, actualBody) -> {
            String traceparent = outgoing.getHeaders().getFirst(CorrelationIdContext.TRACEPARENT_HEADER);
            assertNotNull(traceparent);
            assertTrue(traceparent.startsWith("00-" + parentTraceId + "-"));
            assertTrue(traceparent.endsWith("-01"));
            String[] parts = traceparent.split("-");
            assertEquals(4, parts.length);
            assertEquals(16, parts[2].length());
            assertNotEquals("00f067aa0ba902b7", parts[2]); // new child span
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        })) {
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
    }
}
