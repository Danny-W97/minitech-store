package org.demo.customerservice.export.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="customer.export.cleanup.enabled",havingValue="true",matchIfMissing=true)
public class ExportCleanup {
    private final ExportJobStore store;
    public ExportCleanup(ExportJobStore store){this.store=store;}
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void clean(){for(int batch=0;batch<4;batch++)if(store.cleanup()==0)break;}
}
