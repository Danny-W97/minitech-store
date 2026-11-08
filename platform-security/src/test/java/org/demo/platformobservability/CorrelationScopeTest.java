package org.demo.platformobservability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationScopeTest {
    @AfterEach void clear() { MDC.clear(); }
    @Test void nestedDeliveryRestoresItsRecordAndCallerContext() {
        MDC.put(CorrelationIdContext.MDC_KEY,"caller");MDC.put("unrelated","keep");
        try(var record=CorrelationIdContext.open("record")) {
            try(var delivery=CorrelationIdContext.open("delivery")) { assertEquals("delivery",MDC.get(CorrelationIdContext.MDC_KEY)); }
            assertEquals("record",MDC.get(CorrelationIdContext.MDC_KEY));
        }
        assertEquals("caller",MDC.get(CorrelationIdContext.MDC_KEY));assertEquals("keep",MDC.get("unrelated"));
    }
    @Test void failuresAndRepeatedCloseDoNotLeakTheMessageContext() {
        var scope=CorrelationIdContext.open("record");
        assertThrows(IllegalStateException.class,()->{try(scope) {throw new IllegalStateException("fixture");}});
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
        MDC.put(CorrelationIdContext.MDC_KEY,"next");scope.close();assertEquals("next",MDC.get(CorrelationIdContext.MDC_KEY));
    }
    @Test void absentAndUnsafeRecordIdsNeverInheritTheCallerId() {
        for(String candidate:new String[]{null,"bad\nvalue","a".repeat(101)}) {
            MDC.put(CorrelationIdContext.MDC_KEY,"previous-message");
            try(var scope=CorrelationIdContext.open(candidate)) {
                String actual=MDC.get(CorrelationIdContext.MDC_KEY);assertDoesNotThrow(()->UUID.fromString(actual));
                assertNotEquals("previous-message",actual);
            }
            assertEquals("previous-message",MDC.get(CorrelationIdContext.MDC_KEY));
        }
    }
    @Test void unsafeCallerStateIsClearedRatherThanRestoredIntoLogs() {
        MDC.put(CorrelationIdContext.MDC_KEY,"forged\nline");
        try(var scope=CorrelationIdContext.open("safe")) {assertEquals("safe",MDC.get(CorrelationIdContext.MDC_KEY));}
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
    }
    @Test void outOfOrderCloseCannotClobberTheActiveNestedContext() {
        var outer=CorrelationIdContext.open("outer");var inner=CorrelationIdContext.open("inner");
        assertThrows(IllegalStateException.class,outer::close);assertEquals("inner",MDC.get(CorrelationIdContext.MDC_KEY));
        inner.close();outer.close();assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
    }
    @Test void anotherThreadCannotCloseTheOwnersScope() throws Exception {
        try(var scope=CorrelationIdContext.open("owner");var pool=Executors.newSingleThreadExecutor()) {
            pool.submit(()->{assertThrows(IllegalStateException.class,scope::close);assertNull(MDC.get(CorrelationIdContext.MDC_KEY));}).get();
            assertEquals("owner",MDC.get(CorrelationIdContext.MDC_KEY));
        }
        assertNull(MDC.get(CorrelationIdContext.MDC_KEY));
    }
    @Test void nestedScopePreservesActiveParentTraceIdAndRestoresOuterSpan() {
        String parentTrace = "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01";
        try (var outer = CorrelationIdContext.open("http-request", parentTrace)) {
            assertEquals("1".repeat(32), MDC.get(CorrelationIdContext.MDC_TRACE_ID));
            assertEquals("2".repeat(16), MDC.get(CorrelationIdContext.MDC_SPAN_ID));
            try (var inner = CorrelationIdContext.open("notification-delivery")) {
                assertEquals("notification-delivery", MDC.get(CorrelationIdContext.MDC_KEY));
                assertEquals("1".repeat(32), MDC.get(CorrelationIdContext.MDC_TRACE_ID));
                assertNotEquals("2".repeat(16), MDC.get(CorrelationIdContext.MDC_SPAN_ID));
            }
            assertEquals("http-request", MDC.get(CorrelationIdContext.MDC_KEY));
            assertEquals("1".repeat(32), MDC.get(CorrelationIdContext.MDC_TRACE_ID));
            assertEquals("2".repeat(16), MDC.get(CorrelationIdContext.MDC_SPAN_ID));
        }
        assertNull(MDC.get(CorrelationIdContext.MDC_TRACE_ID));
    }
}
