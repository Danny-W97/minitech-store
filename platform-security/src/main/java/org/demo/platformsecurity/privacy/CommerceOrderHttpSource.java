package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;

public class CommerceOrderHttpSource implements CommerceOrderSource {
    private final String origin;private final RestClient client;private final ObjectMapper json=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public CommerceOrderHttpSource(String origin,ServiceAuthenticationInterceptor authentication){
        var uri=URI.create(origin);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalStateException("Invalid privacy order origin");
        this.origin=origin.replaceAll("/+$","");client=PlatformSecurityConfiguration.client().requestInterceptor(authentication).build();
    }
    public Optional<Entry> next(long customer,UUID request,long after){
        if(customer<1||request==null||after<0)throw new IllegalArgumentException("Invalid commerce scope");
        return client.post().uri(origin+"/internal/privacy/commerce-orders/"+customer).body(Map.of("requestId",request,"cursor",after)).exchange((input,response)->{
            if(response.getStatusCode().value()!=200)throw new IllegalStateException("COMMERCE_SOURCE_UNAVAILABLE");
            byte[] raw=response.getBody().readNBytes(4097);if(raw.length>4096)throw new IllegalStateException("COMMERCE_SOURCE_FORMAT");var result=json.readTree(raw);
            if(result.path("schemaVersion").asInt()!=1||!result.path("customerId").asText().equals(Long.toString(customer))||!result.path("requestId").asText().equals(request.toString()))throw new IllegalStateException("COMMERCE_SOURCE_FORMAT");
            var item=result.get("order");if(item==null)throw new IllegalStateException("COMMERCE_SOURCE_FORMAT");if(item.isNull())return Optional.empty();
            if(!item.path("financeCleared").isBoolean()||!item.path("shipmentExpected").isBoolean()||!item.path("couponExpected").isBoolean()||!item.path("invoiceRequested").isBoolean()||!item.path("orderVersion").isTextual()||!item.path("amount").isTextual())throw new IllegalStateException("COMMERCE_SOURCE_FORMAT");
            long id=Long.parseLong(item.path("orderId").asText());if(id<=after)throw new IllegalStateException("COMMERCE_SOURCE_CURSOR");
            return Optional.of(new Entry(id,item.path("eligibility").asText(),item.path("financeCleared").booleanValue(),item.path("status").asText(),Long.parseLong(item.path("reservationId").asText()),item.path("shipmentExpected").booleanValue(),item.path("couponExpected").booleanValue(),Long.parseLong(item.path("orderVersion").asText()),new java.math.BigDecimal(item.path("amount").asText()),item.path("paymentState").isNull()?null:item.path("paymentState").asText(),item.path("invoiceRequested").booleanValue()));
        });
    }
}
