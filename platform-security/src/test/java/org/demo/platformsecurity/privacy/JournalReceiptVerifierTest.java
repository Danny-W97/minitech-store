package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JournalReceiptVerifierTest {
    final JsonMapper json=JsonMapper.builder().build();
    final UUID ledger=UUID.randomUUID(),request=UUID.randomUUID();
    final KeyPair pair;
    final JournalReceiptVerifier verifier;
    JournalReceiptVerifierTest() throws Exception {
        pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier=new JournalReceiptVerifier(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),ledger.toString());
    }
    ObjectNode capsule(){var c=json.createObjectNode();c.put("schemaVersion",1);c.put("customerId","17");c.put("requestId",request.toString());c.put("confirmedAt","2026-10-29T00:00:00Z");c.put("accountPolicyHash",ErasurePolicies.coreHash());c.put("commercePolicyHash",ErasurePolicies.commerceHash());c.put("financePolicyHash",FinancePolicy.hash());return c;}
    ObjectNode entry(ObjectNode c) throws Exception {
        var e=json.createObjectNode();e.put("schemaVersion",1);e.put("ledgerId",ledger.toString());e.put("sequence",1);e.put("previousHash","0".repeat(64));e.set("capsule",c);
        String frame="A43.37/entry/1\n"+ledger+"\n1\n"+"0".repeat(64)+"\n"+JournalReceiptVerifier.capsuleHash(c)+"\n";
        e.put("entryHash",Access.hash(frame));var signature=Signature.getInstance("Ed25519");signature.initSign(pair.getPrivate());signature.update(frame.getBytes(StandardCharsets.US_ASCII));e.put("signature",Base64.getEncoder().encodeToString(signature.sign()));return e;
    }
    String encoded(ObjectNode e){return Base64.getUrlEncoder().withoutPadding().encodeToString(e.toString().getBytes(StandardCharsets.UTF_8));}
    @Test void signedReceiptRequiresExactIdentityAndReviewedScope() throws Exception {
        var e=entry(capsule());String proof=encoded(e);
        for(String scope:List.of("core","commerce","finance"))assertThat(verifier.verify(proof,17,request,scope)).isEqualTo(e);
        assertThatThrownBy(()->verifier.verify(proof,18,request,"core")).hasMessageContaining("409");
        assertThatThrownBy(()->verifier.verify(proof,17,UUID.randomUUID(),"core")).hasMessageContaining("409");
        var c=capsule();c.putNull("financePolicyHash");String noFinance=encoded(entry(c));
        assertThat(verifier.verify(noFinance,17,request,"core")).isNotNull();
        assertThatThrownBy(()->verifier.verify(noFinance,17,request,"finance")).hasMessageContaining("409");
    }
    @Test void alteredPayloadSignatureLedgerOrOverflowCannotBeAccepted() throws Exception {
        var original=entry(capsule());
        for(String field:List.of("ledgerId","entryHash","signature","previousHash")){
            var changed=original.deepCopy();changed.put(field,"bad");assertThatThrownBy(()->verifier.verify(encoded(changed),17,request,null)).hasMessageContaining("409");
        }
        var changed=original.deepCopy();((ObjectNode)changed.get("capsule")).put("customerId","18");assertThatThrownBy(()->verifier.verify(encoded(changed),18,request,null)).hasMessageContaining("409");
        var overflow=original.deepCopy();overflow.put("sequence",new java.math.BigInteger("9223372036854775809"));assertThatThrownBy(()->verifier.verify(encoded(overflow),17,request,null)).hasMessageContaining("409");
    }
    @Test void missingConfigDuplicateFieldsUnknownDataAndOversizeFailClosed() throws Exception {
        String proof=encoded(entry(capsule()));
        assertThatThrownBy(()->new JournalReceiptVerifier("","").verify(proof,17,request,null)).hasMessageContaining("409");
        assertThatThrownBy(()->verifier.verify("A".repeat(4097),17,request,null)).hasMessageContaining("409");
        var c=capsule();c.put("email","other@example.test");assertThatThrownBy(()->JournalReceiptVerifier.capsuleHash(c)).isInstanceOf(IllegalArgumentException.class);
        String duplicate="{\"schemaVersion\":1,\"schemaVersion\":1}";
        assertThatThrownBy(()->verifier.verify(Base64.getUrlEncoder().withoutPadding().encodeToString(duplicate.getBytes(StandardCharsets.UTF_8)),17,request,null)).hasMessageContaining("409");
    }
}
