package org.demo.paymentservice.web;

import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeWebhookVerifierTest {
    private static final String SECRET="whsec_local-test-secret";
    private static final Instant NOW=Instant.parse("2026-10-25T12:00:00Z");

    @Test void verifiesTheExactRawBodyAndTimestampedSignature() throws Exception {
        byte[] body="{\"id\":\"evt_test_12345678\"}".getBytes(StandardCharsets.UTF_8);
        var verifier=new StripeWebhookVerifier(SECRET,300,Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(verifier.verify(body,signature(body,NOW.getEpochSecond()))).isEqualTo(new String(body,StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verify("{ \"id\":\"evt_test_12345678\"}".getBytes(StandardCharsets.UTF_8),signature(body,NOW.getEpochSecond())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void rejectsStaleSignatures() throws Exception {
        byte[] body="{}".getBytes(StandardCharsets.UTF_8);
        var verifier=new StripeWebhookVerifier(SECRET,60,Clock.fixed(NOW,ZoneOffset.UTC));
        assertThatThrownBy(() -> verifier.verify(body,signature(body,NOW.minusSeconds(61).getEpochSecond())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private static String signature(byte[] body,long timestamp) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
        mac.update((byte)'.');
        return "t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal(body));
    }
}
