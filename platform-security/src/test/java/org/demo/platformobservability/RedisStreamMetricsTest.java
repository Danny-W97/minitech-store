package org.demo.platformobservability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.stream.StreamInfo.*;
import org.springframework.data.redis.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisStreamMetricsTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final StreamOperations<String,Object,Object> streams = mock(StreamOperations.class);
    final Clock clock = mock(Clock.class);
    final AtomicLong time = new AtomicLong(1_000_000);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RedisStreamMetrics bound; // Production keeps the binder as a Spring bean; gauges hold weak references.
    RedisStreamMetrics metrics() {
        when(redis.<Object,Object>opsForStream()).thenReturn(streams);
        when(clock.millis()).thenAnswer(call -> time.get());
        var m = new RedisStreamMetrics(redis,"configured-stream","configured-group",RedisStreamMetrics.Recipient.BILLING,clock);
        bound = m;m.bindTo(registry);return m;
    }
    static XInfoGroup group(String name, long pending, Object lag) {
        return XInfoGroup.fromList(Arrays.asList("name",name,"pending",pending,"lag",lag));
    }
    static XInfoGroups groups(XInfoGroup... values) {
        var rows = new ArrayList<Object>();
        for (var value : values) {
            var row = new ArrayList<Object>();
            value.getRaw().forEach((key, field) -> { row.add(key);row.add(field); });
            rows.add(row);
        }
        return XInfoGroups.fromList(rows);
    }
    double value(String metric) { return registry.get("platform.redis.stream."+metric).gauge().value(); }
    void unknown() {
        assertTrue(Double.isNaN(value("pending")));assertTrue(Double.isNaN(value("lag")));
        assertEquals(0,value("lag.known"));
    }
    @Test void bindingAndScrapingDoNotAccessRedisOrExposeConfiguredNames() {
        metrics();
        unknown();assertEquals(0,value("collector.up"));assertEquals(0,value("collector.last.success.timestamp.seconds"));
        assertTrue(Double.isNaN(value("group.present")));
        assertEquals(6,registry.getMeters().size());
        for(var meter:registry.getMeters()) {
            assertEquals("payments",meter.getId().getTag("stream"));assertEquals("billing",meter.getId().getTag("recipient"));
            assertEquals(2,meter.getId().getTags().size());
            assertFalse(meter.getId().toString().contains("configured-"));
        }
        verifyNoInteractions(redis,streams);
    }
    @Test void pendingAndLagAreDifferentAndScrapingUsesOnlyTheSnapshot() {
        var m=metrics();
        when(streams.groups("configured-stream")).thenReturn(groups(group("unrelated",99,88L),group("configured-group",3,8L)));
        m.collect();
        assertEquals(3,value("pending"));assertEquals(8,value("lag"));assertEquals(1,value("group.present"));
        assertEquals(1,value("collector.up"));assertEquals(1,value("lag.known"));assertEquals(1000,value("collector.last.success.timestamp.seconds"));
        for(int i=0;i<20;i++)registry.getMeters().forEach(meter -> meter.measure().forEach(measurement -> measurement.getValue()));
        verify(redis).opsForStream();verify(streams).groups("configured-stream");verifyNoMoreInteractions(redis,streams);
    }
    @Test void aKnownEmptyGroupIsZeroAndNotAnUnknownResult() {
        var m=metrics();when(streams.groups(anyString())).thenReturn(groups(group("configured-group",0,0L)));m.collect();
        assertEquals(0,value("pending"));assertEquals(0,value("lag"));assertEquals(1,value("lag.known"));assertEquals(1,value("collector.up"));
    }
    @Test void aMissingGroupIsACompletedInspectionWithUnknownCounts() {
        var m=metrics();when(streams.groups(anyString())).thenReturn(groups(group("different-group",0,0L)));m.collect();
        unknown();assertEquals(1,value("collector.up"));assertEquals(0,value("group.present"));assertEquals(1000,value("collector.last.success.timestamp.seconds"));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void absentOrNullLagDoesNotInvalidateKnownPending(boolean includeNull) {
        var m=metrics();var reply=includeNull?group("configured-group",4,null):XInfoGroup.fromList(List.of("name","configured-group","pending",4L));
        when(streams.groups(anyString())).thenReturn(groups(reply));m.collect();
        assertEquals(4,value("pending"));assertTrue(Double.isNaN(value("lag")));assertEquals(0,value("lag.known"));
        assertEquals(1,value("collector.up"));assertEquals(1,value("group.present"));
    }
    @Test void outageRemovesHealthyLookingCountsKeepsLastSuccessAndRecovers() {
        var m=metrics();when(streams.groups(anyString())).thenReturn(groups(group("configured-group",0,0L)));m.collect();
        time.set(2_000_000);when(streams.groups(anyString())).thenThrow(new IllegalStateException("fixture outage"));m.collect();
        unknown();assertEquals(0,value("collector.up"));assertTrue(Double.isNaN(value("group.present")));
        assertEquals(1000,value("collector.last.success.timestamp.seconds"));
        doReturn(groups(group("configured-group",2,5L))).when(streams).groups(anyString());m.collect();
        assertEquals(2,value("pending"));assertEquals(5,value("lag"));assertEquals(1,value("collector.up"));assertEquals(2000,value("collector.last.success.timestamp.seconds"));
    }
    @Test void malformedOrDuplicateMetadataCannotReportSuccess() {
        var m=metrics();
        for(var reply:List.of(groups(group("configured-group",-1,0L)),groups(group("configured-group",0,-1L)),
                groups(group("configured-group",1,1L),group("configured-group",1,1L)))) {
            when(streams.groups(anyString())).thenReturn(reply);m.collect();unknown();assertEquals(0,value("collector.up"));
            assertEquals(0,value("collector.last.success.timestamp.seconds"));
        }
    }
}
