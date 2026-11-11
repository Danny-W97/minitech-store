package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
import java.net.URI;
import java.util.*;

public final class FinanceHttp {
    private final String origin;private final RestClient client;
    private final ObjectMapper json=tools.jackson.databind.json.JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public FinanceHttp(String origin,ServiceAuthenticationInterceptor auth){
        var uri=URI.create(origin);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalStateException("Invalid finance origin");
        this.origin=origin.replaceAll("/+$","");client=PlatformSecurityConfiguration.client().requestInterceptor(auth).build();
    }
    public JsonNode post(String path,Object body){return post(path,body,"");}
    public JsonNode post(String path,Object body,String proof){return client.post().uri(origin+path).header(JournalReceiptVerifier.HEADER,proof).body(body).exchange((request,response)->{
        if(response.getStatusCode().value()!=200)throw new IllegalStateException("FINANCE_PEER_UNAVAILABLE");
        byte[] raw=response.getBody().readNBytes(16385);if(raw.length>16384)throw new IllegalStateException("FINANCE_PEER_FORMAT");return json.readTree(raw);
    });}
    public static void scope(JsonNode data,long customer,UUID request,long order,long version){
        if(data.path("schemaVersion").asInt()!=1||!data.path("customerId").asText().equals(Long.toString(customer))||!data.path("requestId").asText().equals(request.toString())||!data.path("orderId").asText().equals(Long.toString(order))||!data.path("orderVersion").asText().equals(Long.toString(version)))throw new IllegalStateException("FINANCE_PEER_FORMAT");
    }
    public List<String> paymentEvents(long customer,UUID request,long order,long version){
        var data=post("/internal/privacy/finance-events/"+customer+"/"+order,Map.of("requestId",request,"orderVersion",version));scope(data,customer,request,order,version);
        var ids=data.path("eventIds");if(!ids.isArray()||ids.size()>100||!data.path("state").asText().equals("PREPARED"))throw new IllegalStateException("FINANCE_EVENT_REVIEW_REQUIRED");
        var result=new ArrayList<String>();for(var id:ids){if(!id.isTextual()||!id.asText().matches("payment-[0-9a-f]{64}"))throw new IllegalStateException("FINANCE_PEER_FORMAT");result.add(id.asText());}return result;
    }
}
