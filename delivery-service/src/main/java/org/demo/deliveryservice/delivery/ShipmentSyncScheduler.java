package org.demo.deliveryservice.delivery;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="delivery.synchronization.enabled",havingValue="true",matchIfMissing=true)
public class ShipmentSyncScheduler {
    private final ShipmentSyncStateStore states;
    private final ShipmentSyncService sync;
    private final org.demo.platformsecurity.BoundedJobs jobs=new org.demo.platformsecurity.BoundedJobs("shipment-sync",2,40,45000);
    public ShipmentSyncScheduler(ShipmentSyncStateStore states,ShipmentSyncService sync) { this.states=states;this.sync=sync; }
    @Scheduled(fixedDelayString="${delivery.synchronization.delay-ms:5000}") public void recover() {
        for(Long id:states.due()) jobs.submit(id,() -> sync.process(id));
    }
    @jakarta.annotation.PreDestroy void close() { jobs.close(); }
}
