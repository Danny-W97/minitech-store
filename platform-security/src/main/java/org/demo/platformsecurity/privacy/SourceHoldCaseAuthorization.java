package org.demo.platformsecurity.privacy;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Verifies an independently issued scoped case attestation. No signer or boolean approval API. */
public final class SourceHoldCaseAuthorization {
    public record Authorization(UUID id,Instant expiresAt){public boolean active(Clock clock){return !expiresAt.isBefore(clock.instant());}}
    private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final PublicKey key;private final Clock clock;
    public SourceHoldCaseAuthorization(String publicKey){this(publicKey,Clock.systemUTC());}
    public SourceHoldCaseAuthorization(String publicKey,Clock clock){
        this.clock=Objects.requireNonNull(clock);
        if(publicKey==null || publicKey.isBlank()){key=null;return;}
        try{key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));}
        catch(Exception invalid){throw new IllegalStateException("Invalid scoped case authority key");}
    }
    public boolean configured(){return key!=null;}
    public boolean active(Authorization authorization){return authorization.active(clock);}
    static UUID unverifiedClaimedId(SourceHoldRestorationProtocol.Command c){
        try{var parts=c.authorization().split("\\.",-1);if(parts.length!=2)throw new IllegalArgumentException();var p=JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));if(!p.isObject() || p.size()!=4 || !p.path("authorizationId").isTextual() || !p.path("scopeHash").isTextual() || !p.path("scopeHash").asText().equals(SourceHoldRestorationProtocol.scopeHash(c)))throw new IllegalArgumentException();return UUID.fromString(p.path("authorizationId").asText());}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid restoration authorization binding");}
    }
    public Authorization verify(SourceHoldRestorationProtocol.Command c){
        try{
            if(!configured() || c==null || !c.valid() || c.authorization().length()>4096)throw new IllegalArgumentException();
            String[] parts=c.authorization().split("\\.",-1);if(parts.length!=2 || !parts[0].matches("[A-Za-z0-9_-]+") || !parts[1].matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            byte[] raw=Base64.getUrlDecoder().decode(parts[0]);if(raw.length>2048)throw new IllegalArgumentException();var payload=JSON.readTree(raw);
            if(!payload.isObject() || payload.size()!=4)throw new IllegalArgumentException();
            for(String name:List.of("authorizationId","scopeHash","issuedAt","expiresAt"))if(!payload.path(name).isTextual())throw new IllegalArgumentException();
            UUID id=UUID.fromString(payload.path("authorizationId").asText());Instant issued=Instant.parse(payload.path("issuedAt").asText()),expires=Instant.parse(payload.path("expiresAt").asText());
            if(!payload.path("scopeHash").asText().equals(SourceHoldRestorationProtocol.scopeHash(c)) || !expires.isAfter(issued) || Duration.between(issued,expires).compareTo(Duration.ofSeconds(15))>0 || issued.isAfter(clock.instant().plusSeconds(5)))throw new IllegalArgumentException();
            var signature=Signature.getInstance("Ed25519");signature.initVerify(key);signature.update(("A43.40/verified-case/hold-restoration/1\n"+parts[0]).getBytes(StandardCharsets.US_ASCII));if(!signature.verify(Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            return new Authorization(id,expires);
        }catch(Exception invalid){throw new IllegalArgumentException("Scoped case authorization required");}
    }
}
