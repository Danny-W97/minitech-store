package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import tools.jackson.databind.json.JsonMapper;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Synthetic issuer only: no production signer, config file or user's keys. */
final class RestorationCaseFixture {
    private static final KeyPair KEYS;
    static{try{KEYS=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static String publicKey(){return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());}
    static SourceHoldCaseAuthorization verifier(){return new SourceHoldCaseAuthorization(publicKey());}
    static SourceHoldRestorationProtocol.Command authorize(SourceHoldRestorationProtocol.Command c){return authorize(c,UUID.randomUUID(),Instant.now().minusMillis(10));}
    static SourceHoldRestorationProtocol.Command authorize(SourceHoldRestorationProtocol.Command c,UUID id,Instant issued){
        try{
            var payload=Map.of("authorizationId",id.toString(),"scopeHash",SourceHoldRestorationProtocol.scopeHash(c),"issuedAt",issued.toString(),"expiresAt",issued.plusSeconds(15).toString());
            String body=Base64.getUrlEncoder().withoutPadding().encodeToString(JsonMapper.builder().build().writeValueAsBytes(payload));var signer=Signature.getInstance("Ed25519");signer.initSign(KEYS.getPrivate());signer.update(("A43.40/verified-case/hold-restoration/1\n"+body).getBytes(StandardCharsets.US_ASCII));String token=body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
            return new SourceHoldRestorationProtocol.Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),c.bindingEpoch(),c.sourceRevision(),c.challenge(),token,c.mode());
        }catch(Exception e){throw new AssertionError(e);}
    }
    static SourceHoldRestorationProtocol.Command retry(SourceHoldRestorationProtocol.Command c,SourceHoldRestorationProtocol.Mode mode){return new SourceHoldRestorationProtocol.Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),c.bindingEpoch(),c.sourceRevision(),UUID.randomUUID(),c.authorization(),mode);}
}
