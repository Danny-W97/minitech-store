package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.PlatformSecurityConfiguration;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;
import static org.demo.platformsecurity.privacy.PeerRetentionProtocol.*;

public final class PeerRetentionClient {
    private final String origin;private final RestClient client;
    public PeerRetentionClient(String origin,ServiceAuthenticationInterceptor auth){
        try{var u=URI.create(origin);if(!Set.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null || !(u.getPath().isEmpty() || u.getPath().equals("/")) || u.getPort()==0 || u.getPort()>65535)throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid peer evidence origin");}
        this.origin=origin.replaceAll("/+$","");client=PlatformSecurityConfiguration.client().requestInterceptor(Objects.requireNonNull(auth)).build();
    }
    public Reply collect(Command c){if(c==null || !c.valid())throw new IllegalArgumentException("Invalid peer evidence request");try{return client.post().uri(origin+PATH).body(c).exchange((request,response)->{if(response.getStatusCode().value()!=200)throw new IllegalStateException();return parseReply(response.getBody().readNBytes(REPLY_LIMIT+1),c);});}catch(RuntimeException failure){throw new IllegalStateException("PEER_EVIDENCE_UNAVAILABLE");}}
    public PeerRetentionGuardProtocol.Reply guards(PeerRetentionGuardProtocol.Command c){if(c==null || !c.valid())throw new IllegalArgumentException("Invalid guard request");try{return client.post().uri(origin+PeerRetentionGuardProtocol.PATH).body(c).exchange((request,response)->{if(response.getStatusCode().value()!=200)throw new IllegalStateException();return PeerRetentionGuardProtocol.parseReply(response.getBody().readNBytes(PeerRetentionGuardProtocol.REPLY_LIMIT+1),c);});}catch(RuntimeException failure){throw new IllegalStateException("PEER_GUARDS_UNAVAILABLE");}}
    public PeerRetentionFenceProtocol.Reply fence(PeerRetentionFenceProtocol.Command c){if(c==null || !c.valid())throw new IllegalArgumentException("Invalid peer fence request");try{return client.post().uri(origin+PeerRetentionFenceProtocol.PATH).body(c).exchange((request,response)->{if(response.getStatusCode().value()!=200)throw new IllegalStateException();return PeerRetentionFenceProtocol.parseReply(response.getBody().readNBytes(PeerRetentionFenceProtocol.REPLY_LIMIT+1),c);});}catch(RuntimeException failure){throw new IllegalStateException("PEER_FENCE_UNAVAILABLE");}}
    public SourceHoldDeliveryProtocol.Reply deliverHold(SourceHoldDeliveryProtocol.Command c){if(c==null || !c.valid())throw new IllegalArgumentException("Invalid hold delivery request");try{return client.post().uri(origin+SourceHoldDeliveryProtocol.PATH).body(c).exchange((request,response)->{if(response.getStatusCode().value()!=200)throw new IllegalStateException();return SourceHoldDeliveryProtocol.parseReply(response.getBody().readNBytes(SourceHoldDeliveryProtocol.REPLY_LIMIT+1),c);});}catch(RuntimeException failure){throw new IllegalStateException("SOURCE_HOLD_DELIVERY_UNAVAILABLE");}}
    public SourceHoldInspectionProtocol.Reply inspectHold(SourceHoldInspectionProtocol.Command c){if(c==null || !c.valid())throw new IllegalArgumentException("Invalid hold inspection request");try{return client.post().uri(origin+SourceHoldInspectionProtocol.PATH).body(c).exchange((request,response)->{if(response.getStatusCode().value()!=200)throw new IllegalStateException();return SourceHoldInspectionProtocol.parseReply(response.getBody().readNBytes(SourceHoldInspectionProtocol.REPLY_LIMIT+1),c);});}catch(RuntimeException failure){throw new IllegalStateException("SOURCE_HOLD_INSPECTION_UNAVAILABLE");}}
    public SourceHoldRestorationProtocol.Reply restoreHold(SourceHoldRestorationProtocol.Command c){if(c==null || !c.valid())throw new IllegalArgumentException("Invalid hold restoration request");try{return client.post().uri(origin+SourceHoldRestorationProtocol.PATH).body(c).exchange((request,response)->{if(response.getStatusCode().value()!=200)throw new IllegalStateException();return SourceHoldRestorationProtocol.parseReply(response.getBody().readNBytes(SourceHoldRestorationProtocol.REPLY_LIMIT+1),c);});}catch(RuntimeException failure){throw new IllegalStateException("SOURCE_HOLD_RESTORATION_UNAVAILABLE");}}
}
