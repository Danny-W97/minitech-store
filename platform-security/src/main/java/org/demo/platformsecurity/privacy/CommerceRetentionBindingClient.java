package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.PlatformSecurityConfiguration;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionBindingProtocol.*;

/** Fixed authenticated route, 2 KiB response, strict scalar types and complete scope echo. */
public final class CommerceRetentionBindingClient {
    private final String origin;private final RestClient client;
    private final ObjectMapper json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public CommerceRetentionBindingClient(String origin,ServiceAuthenticationInterceptor authentication){
        try {
            URI uri=URI.create(origin);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getPort()==0 || uri.getPort()>65535)throw new IllegalArgumentException();
        }catch(IllegalArgumentException|NullPointerException invalid){throw new IllegalArgumentException("Invalid retention binding origin");}
        this.origin=origin.replaceAll("/+$","");client=PlatformSecurityConfiguration.client().requestInterceptor(Objects.requireNonNull(authentication)).build();
    }
    public Reply bind(Subject subject,Phase phase,long expectedEpoch){
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);
        var command=new Command(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase,expectedEpoch);
        if(!command.valid())throw new IllegalArgumentException("Invalid binding scope");
        try {
            return client.post().uri(origin+PATH).body(command).exchange((request,response)->{
                if(response.getStatusCode().value()!=200)throw new IllegalStateException("RETENTION_BINDING_UNAVAILABLE");
                byte[] raw=response.getBody().readNBytes(2049);if(raw.length>2048)throw new IllegalStateException("RETENTION_BINDING_PROTOCOL");
                JsonNode data=json.readTree(raw);
                if(data==null || !data.isObject() || data.size()!=8)throw new IllegalStateException("RETENTION_BINDING_PROTOCOL");
                for(String key:List.of("orderId","orderVersion","expectedEpoch","bindingEpoch","sourceRevision"))
                    if(!data.path(key).isIntegralNumber() || !data.path(key).canConvertToLong())throw new IllegalStateException("RETENTION_BINDING_PROTOCOL");
                if(!data.path("generation").isTextual() || !data.path("phase").isTextual() || !data.path("invalidated").isBoolean()
                        || data.path("orderId").longValue()!=subject.orderId() || data.path("orderVersion").longValue()!=subject.orderVersion()
                        || !data.path("generation").asText().equals(subject.chainGeneration().toString()) || !data.path("phase").asText().equals(phase.name())
                        || data.path("expectedEpoch").longValue()!=expectedEpoch || data.path("bindingEpoch").longValue()!=expectedEpoch+1
                        || data.path("sourceRevision").longValue()<0)throw new IllegalStateException("RETENTION_BINDING_PROTOCOL");
                return new Reply(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),phase,expectedEpoch,
                        data.path("bindingEpoch").longValue(),data.path("sourceRevision").longValue(),data.path("invalidated").booleanValue());
            });
        }catch(RuntimeException failure){
            // Do not propagate peer bodies, credentials, origins or parsing exception causes.
            throw new IllegalStateException("RETENTION_BINDING_UNAVAILABLE");
        }
    }
}
