package org.demo.platformsecurity.privacy;

import java.util.UUID;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** No customer identity, proof state or arbitrary payload crosses this protocol. */
public final class CommerceRetentionBindingProtocol {
    private CommerceRetentionBindingProtocol() {}
    public static final String PATH="/internal/privacy/commerce-retention/bind";
    public record Command(Long orderId,UUID generation,Long orderVersion,Phase phase,Long expectedEpoch) {
        public boolean valid(){return orderId!=null && orderId>0 && generation!=null && orderVersion!=null && orderVersion>=0
                && phase!=null && expectedEpoch!=null && expectedEpoch>=0 && expectedEpoch<Long.MAX_VALUE;}
        public Subject subject(){if(!valid())throw new IllegalArgumentException("Invalid binding scope");return new Subject(orderId,0,orderVersion,generation);}
    }
    public record Reply(long orderId,UUID generation,long orderVersion,Phase phase,long expectedEpoch,
                        long bindingEpoch,long sourceRevision,boolean invalidated) {}
}
