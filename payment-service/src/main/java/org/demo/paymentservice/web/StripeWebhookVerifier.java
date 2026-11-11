package org.demo.paymentservice.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Verifies Stripe's timestamped v1 signature against the untouched request bytes. */
@Component
public final class StripeWebhookVerifier {
    private final String secret;
    private final long toleranceSeconds;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public StripeWebhookVerifier(@Value("${payment.stripe.webhook-secret:}") String secret,
            @Value("${payment.stripe.signature-tolerance-seconds:300}") long toleranceSeconds) {
        this(secret,toleranceSeconds,Clock.systemUTC());
    }

    StripeWebhookVerifier(String secret,long toleranceSeconds,Clock clock) {
        this.secret=secret==null ? "" : secret;
        if(toleranceSeconds<60 || toleranceSeconds>900) throw new IllegalArgumentException("Invalid Stripe signature tolerance");
        this.toleranceSeconds=toleranceSeconds;this.clock=clock;
    }

    public String verify(byte[] payload,String signatureHeader) {
        if(secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe webhook is not configured");
        if(payload==null || payload.length==0 || payload.length>1_048_576 || signatureHeader==null || signatureHeader.length()>8192)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe webhook request");
        Long timestamp=null;List<String> signatures=new ArrayList<>();
        for(String part:signatureHeader.split(",",-1)) {
            String[] pair=part.split("=",2);if(pair.length!=2) continue;
            if("t".equals(pair[0])) {
                if(timestamp!=null) throw invalid();
                try { timestamp=Long.parseLong(pair[1]); } catch(NumberFormatException malformed) { throw invalid(); }
            } else if("v1".equals(pair[0]) && pair[1].matches("[0-9a-fA-F]{64}")) signatures.add(pair[1]);
        }
        long now=clock.instant().getEpochSecond();
        if(timestamp==null || timestamp<1 || Math.abs(now-timestamp)>toleranceSeconds || signatures.isEmpty()) throw invalid();
        byte[] expected=signature(timestamp,payload);
        boolean match=false;
        for(String provided:signatures) {
            try { match |= MessageDigest.isEqual(expected,HexFormat.of().parseHex(provided)); }
            catch(IllegalArgumentException ignored) { /* malformed candidate is not a match */ }
        }
        if(!match) throw invalid();
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString();
        } catch(java.nio.charset.CharacterCodingException malformed) { throw invalid(); }
    }

    private byte[] signature(long timestamp,byte[] payload) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));mac.update((byte)'.');return mac.doFinal(payload);
        } catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException("HMAC is unavailable",impossible); }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe webhook signature"); }
}
