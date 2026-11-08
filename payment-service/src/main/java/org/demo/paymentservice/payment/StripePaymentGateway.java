package org.demo.paymentservice.payment;

import org.demo.platformsecurity.BoundedHttpRequestFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Stripe hosted Checkout adapter. Card data is entered on Stripe and never reaches this service. */
public final class StripePaymentGateway implements PaymentGateway {
    private static final Set<PaymentMethod> METHODS=Set.of(PaymentMethod.CARD,PaymentMethod.CASH_ON_DELIVERY,PaymentMethod.CASH_AT_PICKUP);
    private final String secretKey;
    private final String returnBaseUrl;
    private final boolean liveMode;
    private final RestClient client;

    public StripePaymentGateway(String secretKey,String returnBaseUrl,String apiBaseUrl,boolean production) {
        this(secretKey,returnBaseUrl,apiBaseUrl,production,null);
    }
    StripePaymentGateway(String secretKey,String returnBaseUrl,String apiBaseUrl,boolean production,RestClient suppliedClient) {
        if(secretKey==null || !(secretKey.startsWith("sk_test_") || secretKey.startsWith("sk_live_")))
            throw new IllegalStateException("Stripe requires a secret API key from the selected Stripe mode");
        this.liveMode=secretKey.startsWith("sk_live_");
        if(production!=liveMode) throw new IllegalStateException("Stripe test keys are restricted to non-production and live keys are restricted to production");
        this.secretKey=secretKey;
        this.returnBaseUrl=validateBase(returnBaseUrl,"payment return URL",production);
        String api=validateBase(apiBaseUrl,"Stripe API URL",production);
        // Construct this client directly: injected service RestClient builders carry internal
        // service-auth headers and must never be reused for a third-party API.
        if(production && !"https://api.stripe.com".equals(api)) throw new IllegalStateException("Production Stripe API must use api.stripe.com");
        this.client=suppliedClient!=null ? suppliedClient : RestClient.builder().baseUrl(api).requestFactory(new BoundedHttpRequestFactory(2000,10000)).build();
    }

    @Override public String provider() { return "stripe"; }
    @Override public Set<PaymentMethod> supportedMethods() { return METHODS; }
    public boolean liveMode() { return liveMode; }
    @Override public void requireAvailable() {}
    @Override public void validatePayment(BigDecimal amount,String currency,PaymentMethod method) { checkoutAmount(amount,currency,method); }

    @Override public Authorization authorize(Long orderId,BigDecimal amount,String currency,PaymentMethod method,
            String idempotencyKey,boolean simulateFailure) {
        return authorize(orderId,amount,currency,method,idempotencyKey,java.time.Instant.now().plusSeconds(31*60));
    }
    public Authorization authorize(Long orderId,BigDecimal amount,String currency,PaymentMethod method,
            String idempotencyKey,java.time.Instant expiresAt) {
        if(orderId==null || orderId<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe order reference");
        long minor=checkoutAmount(amount,currency,method);

        MultiValueMap<String,String> form=new LinkedMultiValueMap<>();
        form.add("mode","payment");
        form.add("payment_method_types[0]","card");
        form.add("line_items[0][price_data][currency]","pln");
        form.add("line_items[0][price_data][unit_amount]",Long.toString(minor));
        form.add("line_items[0][price_data][product_data][name]","MiniTech order #"+orderId);
        form.add("line_items[0][quantity]","1");
        form.add("client_reference_id",Long.toString(orderId));
        form.add("metadata[order_id]",Long.toString(orderId));
        form.add("payment_intent_data[metadata][order_id]",Long.toString(orderId));
        form.add("success_url",returnUrl("success",orderId));
        form.add("cancel_url",returnUrl("cancelled",orderId));
        // Keep the stock reservation alive past Stripe's session expiry. The small
        // buffer covers webhook delivery and normal clock skew.
        form.add("expires_at",Long.toString(expiresAt.getEpochSecond()));

        try {
            JsonNode response=client.post().uri("/v1/checkout/sessions")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> {
                        headers.setBasicAuth(secretKey,"");
                        headers.set("Idempotency-Key",stripeIdempotencyKey(orderId,idempotencyKey));
                    })
                    .body(form).retrieve().body(JsonNode.class);
            String sessionId=value(response,"id"),url=value(response,"url");
            if(sessionId==null || !sessionId.startsWith("cs_") || url==null || !url.startsWith("https://checkout.stripe.com/"))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe returned an invalid Checkout Session");
            return new Authorization(true,sessionId,null,url);
        } catch(RestClientResponseException failure) {
            if(failure.getStatusCode().value()==400) {
                try {
                    var error=failure.getResponseBodyAs(JsonNode.class);
                    String parameter=error==null ? null : value(error.path("error"),"param");
                    if("expires_at".equals(parameter)) return new Authorization(false,null,"Stripe Checkout creation window expired");
                    if(parameter!=null && parameter.startsWith("line_items") && "invalid_request_error".equals(value(error.path("error"),"type")))
                        return new Authorization(false,null,"Stripe rejected the Checkout amount");
                } catch(RuntimeException unreadable) { /* Unknown provider results remain unresolved. */ }
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe could not create Checkout");
        } catch(RestClientException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe is temporarily unavailable");
        }
    }
    private static long checkoutAmount(BigDecimal amount,String currency,PaymentMethod method) {
        if(method!=PaymentMethod.CARD) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stripe Checkout currently supports card payments only");
        if(amount==null || amount.signum()<=0 || !"PLN".equalsIgnoreCase(currency))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe payment amount or currency");
        long minor;
        try { minor=amount.movePointRight(2).longValueExact(); }
        catch(ArithmeticException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe payment amount"); }
        if(minor<1 || minor>Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stripe payment amount is outside the supported range");
        return minor;
    }

    public record Session(String id,Long orderId,long amount,String currency,String intent,String paymentStatus,String status) {}
    public record Refund(String id,String intent,long amount,String currency,String status,String operationId) {}

    public Session session(String id) {
        requireId(id,"cs_");
        JsonNode data=get("/v1/checkout/sessions/"+id);
        String order=value(data.path("metadata"),"order_id");
        Long orderId;
        try { orderId=Long.valueOf(order); } catch(RuntimeException bad) { throw new StripeReviewRequiredException("SESSION_IDENTITY"); }
        if(!id.equals(value(data,"id")) || !"checkout.session".equals(value(data,"object"))
                || !"payment".equals(value(data,"mode")) || !order.equals(value(data,"client_reference_id"))
                || !data.path("livemode").isBoolean() || data.path("livemode").booleanValue()!=liveMode
                || number(data,"amount_total")<1 || !"pln".equals(value(data,"currency")))
            throw new StripeReviewRequiredException("SESSION_IDENTITY");
        String intent=value(data,"payment_intent");
        if("paid".equals(value(data,"payment_status"))) requireId(intent,"pi_");
        return new Session(id,orderId,number(data,"amount_total"),value(data,"currency"),intent,value(data,"payment_status"),value(data,"status"));
    }

    public Refund refund(String id) {
        requireId(id,"re_");var refund=refundObject(get("/v1/refunds/"+id));
        if(!id.equals(refund.id())) throw new StripeReviewRequiredException("REFUND_IDENTITY_CONFLICT");
        return refund;
    }
    public Long orderForIntent(String id) {
        requireId(id,"pi_");var data=get("/v1/payment_intents/"+id);
        if(data==null || !id.equals(value(data,"id")) || !"payment_intent".equals(value(data,"object"))
                || !data.path("livemode").isBoolean() || data.path("livemode").booleanValue()!=liveMode)
            throw new StripeReviewRequiredException("EXTERNAL_REFUND_INTENT_INVALID");
        String order=value(data.path("metadata"),"order_id");
        if(order==null) return null; // Other applications may share the Stripe account.
        try { return Long.valueOf(order); }
        catch(RuntimeException bad) { throw new StripeReviewRequiredException("EXTERNAL_REFUND_ORDER_UNKNOWN"); }
    }

    /** Reconcile a lost POST response before reusing its provider idempotency key. */
    public Refund findRefund(String intent,String operation) {
        requireId(intent,"pi_");String after=null;boolean externalRefund=false;
        for(int page=0;page<10;page++) {
            final String cursor=after;
            JsonNode data;
            try {
                data=client.get().uri(builder -> {
                    var uri=builder.path("/v1/refunds").queryParam("payment_intent",intent).queryParam("limit",100);
                    if(cursor!=null) uri.queryParam("starting_after",cursor);
                    return uri.build();
                }).headers(headers -> headers.setBasicAuth(secretKey,"")).retrieve().body(JsonNode.class);
            } catch(RestClientException unavailable) { throw unavailable(); }
            if(data==null || !data.path("data").isArray() || !data.path("has_more").isBoolean()) throw new StripeReviewRequiredException("REFUND_LIST_INVALID");
            for(JsonNode row:data.path("data")) {
                var refund=refundObject(row);
                if(operation.equals(refund.operationId())) return refund;
                if(!java.util.Set.of("failed","canceled").contains(refund.status())) externalRefund=true;
                after=refund.id();
            }
            if(!data.path("has_more").booleanValue()) {
                if(externalRefund) throw new StripeReviewRequiredException("EXTERNAL_REFUND_REQUIRES_RECONCILIATION");
                return null;
            }
            if(after==null || after.equals(cursor)) break;
        }
        throw new StripeReviewRequiredException("REFUND_LIST_LIMIT");
    }

    public Refund createRefund(String intent,long amount,String operationId,Long paymentId) {
        requireId(intent,"pi_");
        MultiValueMap<String,String> form=new LinkedMultiValueMap<>();
        form.add("payment_intent",intent);form.add("amount",Long.toString(amount));
        form.add("metadata[operation_id]",operationId);form.add("metadata[payment_id]",paymentId.toString());
        try {
            var data=client.post().uri("/v1/refunds").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> { headers.setBasicAuth(secretKey,"");headers.set("Idempotency-Key","minitech-refund-"+operationId); })
                    .body(form).retrieve().body(JsonNode.class);
            return refundObject(data);
        } catch(RestClientResponseException error) {
            if(error.getStatusCode().is4xxClientError() && error.getStatusCode().value()!=429)
                throw new StripeReviewRequiredException("STRIPE_REFUND_HTTP_"+error.getStatusCode().value());
            throw unavailable();
        } catch(RestClientException error) { throw unavailable(); }
    }

    private JsonNode get(String path) {
        try { return client.get().uri(path).headers(headers -> headers.setBasicAuth(secretKey,"")).retrieve().body(JsonNode.class); }
        catch(RestClientException error) { throw unavailable(); }
    }
    private Refund refundObject(JsonNode data) {
        if(data==null || !"refund".equals(value(data,"object")) || number(data,"amount")<1
                || value(data,"status")==null) throw new StripeReviewRequiredException("REFUND_INVALID");
        requireId(value(data,"id"),"re_");requireId(value(data,"payment_intent"),"pi_");
        return new Refund(value(data,"id"),value(data,"payment_intent"),number(data,"amount"),value(data,"currency"),
                value(data,"status"),value(data.path("metadata"),"operation_id"));
    }
    private static long number(JsonNode data,String field) {
        var node=data==null ? null : data.get(field);
        return node!=null && node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : -1;
    }
    private static void requireId(String id,String prefix) {
        if(id==null || !id.matches(prefix+"[A-Za-z0-9_]{4,96}")) throw new StripeReviewRequiredException("PROVIDER_REFERENCE_INVALID");
    }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe operation could not be confirmed; retry is pending"); }

    private String returnUrl(String state,long orderId) {
        String separator=returnBaseUrl.contains("?") ? "&" : "?";
        return returnBaseUrl+separator+"paymentReturn="+state+"&paymentOrder="+orderId;
    }

    private static String stripeIdempotencyKey(Long orderId,String key) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((orderId+":"+key).getBytes(StandardCharsets.UTF_8));
            return "minitech-checkout-"+orderId+"-"+HexFormat.of().formatHex(digest);
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String value(JsonNode node,String field) {
        JsonNode value=node==null ? null : node.get(field);
        return value==null || value.isNull() ? null : value.asText();
    }

    private static String validateBase(String value,String label,boolean production) {
        if(value==null || value.isBlank()) throw new IllegalStateException("Stripe requires a configured "+label);
        URI uri;
        try { uri=URI.create(value); } catch(IllegalArgumentException invalid) { throw new IllegalStateException("Invalid "+label); }
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || (production && !"https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalStateException("Invalid "+label);
        return value.endsWith("/") ? value.substring(0,value.length()-1) : value;
    }
}
