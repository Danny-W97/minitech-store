package org.demo.billingservice.messaging.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InvoiceStreamRetentionWorker {
    private final InvoiceStreamRetentionRepository repository;
    private final InvoiceStreamRetentionProperties properties;
    private final Clock clock;
    private final AtomicLong length=new AtomicLong(-1),oldest=new AtomicLong(-1),eligible=new AtomicLong(-1),pending=new AtomicLong(-1),blocked=new AtomicLong(-1),groups=new AtomicLong(-1),lastSuccess=new AtomicLong(-1),lastDeleted=new AtomicLong(-1);
    private final Counter deleted,succeeded,failed;
    @Autowired public InvoiceStreamRetentionWorker(InvoiceStreamRetentionRepository repository,InvoiceStreamRetentionProperties properties,MeterRegistry registry) {
        this(repository,properties,registry,Clock.systemUTC());
    }
    InvoiceStreamRetentionWorker(InvoiceStreamRetentionRepository repository,InvoiceStreamRetentionProperties properties,MeterRegistry registry,Clock clock) {
        this.repository=repository;this.properties=properties;this.clock=clock;
        deleted=registry.counter("billing.invoice.stream.retention.deleted");
        succeeded=registry.counter("billing.invoice.stream.retention.runs","outcome","success");failed=registry.counter("billing.invoice.stream.retention.runs","outcome","failed");
        Gauge.builder("billing.invoice.stream.retention.enabled",properties,p->p.mode()==InvoiceStreamRetentionProperties.Mode.OFF?0:1).register(registry);
        Gauge.builder("billing.invoice.stream.retention.deletion.enabled",properties,p->p.mode()==InvoiceStreamRetentionProperties.Mode.TRIM?1:0).register(registry);
        Gauge.builder("billing.invoice.stream.retention.age.seconds",properties,p->p.retention().getSeconds()).register(registry);
        meter(registry,"length",length);meter(registry,"oldest.stored.age.seconds",oldest);meter(registry,"eligible",eligible);meter(registry,"pending",pending);
        meter(registry,"blocked.code",blocked);meter(registry,"group.count",groups);meter(registry,"last.success.timestamp.seconds",lastSuccess);meter(registry,"last.deleted",lastDeleted);
    }
    private static void meter(MeterRegistry registry,String name,AtomicLong value) {Gauge.builder("billing.invoice.stream.retention."+name,value,AtomicLong::get).register(registry);}
    @Scheduled(fixedDelayString="${messaging.invoice-stream-retention.interval-ms:600000}",initialDelayString="${messaging.invoice-stream-retention.initial-delay-ms:60000}")
    public void cleanup() {
        if(properties.mode()==InvoiceStreamRetentionProperties.Mode.OFF) return;
        long now=clock.millis(),cutoff=Math.max(0,now-properties.retention().toMillis()),total=0;
        boolean trimming=properties.mode()==InvoiceStreamRetentionProperties.Mode.TRIM;
        try {
            InvoiceStreamRetentionRepository.Snapshot snapshot=null;
            for(int batch=0;batch<(trimming?properties.maxBatches():1);batch++) {
                snapshot=repository.run(cutoff,trimming,properties.batchSize(),properties.groups(),properties.allowNoGroups());
                total+=snapshot.removed();deleted.increment(snapshot.removed());
                if(!trimming || snapshot.removed()<properties.batchSize() || !snapshot.eligible() || snapshot.blocked()!=InvoiceStreamRetentionRepository.READY) break;
            }
            length.set(snapshot.length());oldest.set(snapshot.oldestAgeSeconds(now));eligible.set(snapshot.eligible()?1:0);pending.set(snapshot.old()?1:0);
            blocked.set(snapshot.blocked());groups.set(snapshot.groups());lastDeleted.set(total);lastSuccess.set(now/1000);succeeded.increment();
        } catch(DataAccessException | IllegalStateException error) {
            failed.increment();org.slf4j.LoggerFactory.getLogger(getClass()).warn("Invoice stream retention failed ({})",error.getClass().getSimpleName());
        }
    }
}
