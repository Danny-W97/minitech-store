package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.PlatformSecurityConfiguration;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;
import static org.demo.platformsecurity.privacy.InventoryRetentionProtocol.*;

/** Service-authenticated, bounded observation. No retry or redirect. */
public final class InventoryRetentionClient {
    private final String origin;private final RestClient client;
    public InventoryRetentionClient(String origin,ServiceAuthenticationInterceptor authentication){
        try{var u=URI.create(origin);if(!Set.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null
                || u.getQuery()!=null || u.getFragment()!=null || !(u.getPath().isEmpty() || u.getPath().equals("/")) || u.getPort()==0 || u.getPort()>65535)throw new IllegalArgumentException();}
        catch(IllegalArgumentException|NullPointerException invalid){throw new IllegalArgumentException("Invalid inventory evidence origin");}
        this.origin=origin.replaceAll("/+$","");client=PlatformSecurityConfiguration.client().requestInterceptor(Objects.requireNonNull(authentication)).build();
    }
    public Reply collect(Command command){
        if(command==null || !command.valid())throw new IllegalArgumentException("Invalid inventory evidence request");
        try{return client.post().uri(origin+PATH).body(command).exchange((request,response)->{
            if(response.getStatusCode().value()!=200)throw new IllegalStateException();
            return parseReply(response.getBody().readNBytes(REPLY_LIMIT+1),command);
        });}catch(RuntimeException failure){throw new IllegalStateException("INVENTORY_EVIDENCE_UNAVAILABLE");}
    }
}
