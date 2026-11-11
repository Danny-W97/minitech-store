package org.demo.platformsecurity.privacy;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommerceRetentionFieldScopeTest {
    byte[] resource()throws Exception{try(var in=getClass().getResourceAsStream(CommerceRetentionFieldScope.RESOURCE)){return in.readAllBytes();}}
    @Test void packagedDefinitionHasItsReviewedDigest()throws Exception{assertThat(CommerceRetentionFieldScope.verifiedDigest()).isEqualTo(CommerceRetentionFieldScope.SHA256);assertThat(CommerceRetentionFieldScope.verify(resource())).isEqualTo(CommerceRetentionFieldScope.SHA256);}
    @Test void windowsCheckoutLineEndingsDoNotChangeTheDefinition()throws Exception{assertThat(CommerceRetentionFieldScope.verify(new String(resource(),StandardCharsets.UTF_8).replace("\n","\r\n").getBytes(StandardCharsets.UTF_8))).isEqualTo(CommerceRetentionFieldScope.SHA256);}
    @Test void alteredPolicyFieldsAndDecisionsAreRejectedWithoutEchoingInput()throws Exception{
        var original=new String(resource(),StandardCharsets.UTF_8);
        for(var change:List.of(original.replace("delivery_date","unreviewed_field"),original.replace("365","364"),original.replace("FIELD_SCOPE_REVIEWED","PRESERVATION_POLICY_REVIEWED"),original+"sensitive-fixture-never-echo"))assertThatThrownBy(()->CommerceRetentionFieldScope.verify(change.getBytes(StandardCharsets.UTF_8))).hasMessage("RETENTION_FIELD_SCOPE_UNAVAILABLE");
    }
    @Test void missingEmptyOversizedAndMalformedDefinitionsFailClosed(){
        for(byte[] raw:Arrays.asList(null,new byte[0],new byte[65537],new byte[]{(byte)0xff},"{}".getBytes(StandardCharsets.UTF_8)))assertThatThrownBy(()->CommerceRetentionFieldScope.verify(raw)).hasMessage("RETENTION_FIELD_SCOPE_UNAVAILABLE");
    }
}
