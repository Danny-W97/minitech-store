package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionBindingClient;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import static org.demo.orderservice.privacy.CommerceRetentionBindings.*;

/** Drains already prepared commands only. Never scans accounts, creates generations or writes proofs. */
public final class CommerceRetentionBindingPublisher {
    private final CommerceRetentionBindings coordinator;private final Map<Source,CommerceRetentionBindingClient> clients;
    public CommerceRetentionBindingPublisher(CommerceRetentionBindings coordinator,Map<Source,CommerceRetentionBindingClient> clients,long delay){
        if(delay<100 || delay>60000 || !clients.keySet().equals(EnumSet.allOf(Source.class)))throw new IllegalArgumentException("Invalid binding publisher configuration");
        this.coordinator=Objects.requireNonNull(coordinator);this.clients=Map.copyOf(clients);
    }
    @Scheduled(fixedDelayString="${privacy.commerce-retention-binding.publish-delay-ms:1000}",initialDelayString="${privacy.commerce-retention-binding.publish-delay-ms:1000}")
    public void tick(){try{publishOne();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("RETENTION_BINDING_TICK_FAILED");}}
    public boolean publishOne(){return coordinator.publishOne((source,scope,phase,epoch)->{
        var reply=clients.get(source).bind(scope,phase,epoch);return new Acknowledgement(reply.bindingEpoch(),reply.sourceRevision(),reply.invalidated());
    });}
}
