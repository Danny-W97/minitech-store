package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** A signed write receipt, not a read capability. Private service authorization is still mandatory. */
public final class JournalReceiptVerifier {
    public static final String HEADER="X-Privacy-Journal";
    private final PublicKey key;private final UUID ledger;
    private final ObjectMapper json=tools.jackson.databind.json.JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public JournalReceiptVerifier(String publicKey,String ledgerId){
        if(publicKey.isBlank()&&ledgerId.isBlank()){key=null;ledger=null;return;}
        try{ledger=UUID.fromString(ledgerId);key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));}
        catch(Exception invalid){throw new IllegalStateException("Invalid privacy journal verification configuration");}
    }
    public UUID ledgerId(){return ledger;}
    public String publicKeyFingerprint(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));}catch(Exception invalid){throw new IllegalStateException("Journal key unavailable");}}
    public boolean configured(){return key!=null;}
    public static String capsuleHash(JsonNode c){
        if(!c.isObject()||c.size()!=7||!c.path("schemaVersion").isIntegralNumber()||c.path("schemaVersion").asInt()!=1)throw new IllegalArgumentException("Invalid journal capsule");
        String customer=c.path("customerId").asText();if(!customer.matches("[1-9][0-9]{0,18}")||Long.parseLong(customer)<1)throw new IllegalArgumentException("Invalid journal subject");
        String request=UUID.fromString(c.path("requestId").asText()).toString();
        return Access.hash("A43.37/account/1\n"+customer+"\n"+request+"\n"+java.time.Instant.parse(c.path("confirmedAt").asText()).toString()+"\n"+policy(c,"accountPolicyHash")+"\n"+policy(c,"commercePolicyHash")+"\n"+policy(c,"financePolicyHash")+"\n");
    }
    private static String policy(JsonNode c,String name){var value=c.get(name);if(value==null)throw new IllegalArgumentException("Missing journal policy");if(value.isNull())return "-";if(!value.isTextual()||!value.asText().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid journal policy");return value.asText();}
    public JsonNode verify(String encoded,long customer,UUID request,String scope){
        try{
            if(!configured()||encoded==null||encoded.length()>4096||!encoded.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            var entry=json.readTree(Base64.getUrlDecoder().decode(encoded));
            if(!entry.isObject()||entry.size()!=7||!entry.path("schemaVersion").isIntegralNumber()||entry.path("schemaVersion").asInt()!=1||!entry.path("ledgerId").asText().equals(ledger.toString())||!entry.path("sequence").isIntegralNumber()||entry.path("sequence").asLong()<1)throw new IllegalArgumentException();
            long sequence=Long.parseLong(entry.path("sequence").asText());if(sequence<1)throw new IllegalArgumentException();
            var capsule=entry.path("capsule");String hash=capsuleHash(capsule);
            if(!capsule.path("customerId").asText().equals(Long.toString(customer))||!capsule.path("requestId").asText().equals(request.toString()))throw new IllegalArgumentException();
            String previous=entry.path("previousHash").asText();if(!previous.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            String frame="A43.37/entry/1\n"+ledger+"\n"+sequence+"\n"+previous+"\n"+hash+"\n";
            if(!Access.hash(frame).equals(entry.path("entryHash").asText()))throw new IllegalArgumentException();
            var signature=Signature.getInstance("Ed25519");signature.initVerify(key);signature.update(frame.getBytes(StandardCharsets.US_ASCII));
            if(!signature.verify(Base64.getDecoder().decode(entry.path("signature").asText())))throw new IllegalArgumentException();
            if(scope!=null){String expected=switch(scope){case "core"->ErasurePolicies.coreHash();case "commerce"->ErasurePolicies.commerceHash();case "finance"->FinancePolicy.hash();default->throw new IllegalArgumentException();};
                String field=scope.equals("core")?"accountPolicyHash":scope.equals("commerce")?"commercePolicyHash":"financePolicyHash";
                if(!expected.equals(capsule.path(field).asText()))throw new IllegalArgumentException();}
            return entry;
        }catch(Exception invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"Current signed privacy journal receipt required");}
    }
    public void require(long customer,UUID request,String scope){verify(Access.header(HEADER),customer,request,scope);}
}
