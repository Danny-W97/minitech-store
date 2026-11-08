package org.demo.billingservice.billing;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class PdfCacheSchedulingTest {
    @Configuration @EnableScheduling static class Fixture {
        @Bean AtomicLong clock(){return new AtomicLong();}
        @Bean PdfDocumentCache cache(AtomicLong clock){return new PdfDocumentCache(100,10,Duration.ofNanos(10),clock::get);}
    }
    @Test void springEvictsAnIdleExpiredPdfWithoutAnyRead() throws Exception {
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("billing.pdf.cache.sweep-ms","10")));
            context.register(Fixture.class);context.refresh();
            var cache=context.getBean(PdfDocumentCache.class);var clock=context.getBean(AtomicLong.class);
            cache.put(PdfDocumentCache.Kind.INVOICE,1L,new byte[]{1,2,3});clock.set(10);
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();boolean empty=false;
            while(System.nanoTime()<deadline){synchronized(cache){empty=((Map<?,?>)ReflectionTestUtils.getField(cache,"entries")).isEmpty();}if(empty)break;Thread.sleep(10);}
            assertThat(empty).isTrue();
        }
    }
}
