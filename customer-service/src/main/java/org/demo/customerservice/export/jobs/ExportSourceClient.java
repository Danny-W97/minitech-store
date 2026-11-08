package org.demo.customerservice.export.jobs;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.customerservice.export.*;
import org.demo.platformsecurity.*;
import org.demo.platformobservability.CorrelationIdClientInterceptor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.http.MediaType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class ExportSourceClient {
    public static final int MAX_PART_BYTES=524288;
    private final RestClient client,orderProof;private final Map<String,String> urls;private final ExportJson json;
    private final AccountDataExportService profile;private final SecurityDataExportService security;
    public ExportSourceClient(Environment env,ExportJson json,AccountDataExportService profile,SecurityDataExportService security){
        this.json=json;this.profile=profile;this.security=security;
        var base=RestClient.builder().requestFactory(new BoundedHttpRequestFactory(1000,3000)).requestInterceptor(new CorrelationIdClientInterceptor());
        // Deliberately do not use the platform-managed builder: those clients replace the caller's identity.
        client=base.clone().build();
        urls=new HashMap<>();String[] names={"order","inventory","payment","notification","delivery","promotion","wishlist","review","recommendation","returns","billing"};
        int[] ports={18082,18083,18084,18086,18088,18089,18090,18091,18092,18094,18095};
        for(int i=0;i<names.length;i++){String value=env.getProperty(names[i].toUpperCase(Locale.ROOT)+"_SERVICE_URL","http://localhost:"+ports[i]);
            URI uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalStateException("Invalid export service origin");
            urls.put(names[i],value.replaceAll("/+$",""));}
        orderProof=base.clone().baseUrl(urls.get("order")).requestInterceptor(new ServiceAuthenticationInterceptor(env.getProperty("PLATFORM_SERVICE_TOKEN",""))).build();
    }
    public ExportTask.Result fetch(ExportTask task,CustomerPrincipal owner,String bearer){
        Instant started=Instant.now();RequestBudget.check();
        if(task.kind().local()){
            Object value=switch(task.kind()){
                case PROFILE -> profile.export(owner);case METHODS -> security.methods(owner);
                case EVENTS -> security.events(owner,task.cursor(),50);case PASSKEYS -> security.passkeys(owner,task.cursor(),50);
                case RECOVERY -> security.recovery(owner,task.cursor(),50);default -> throw new ExportFailure("SOURCE_FORMAT");};
            String data=json.write(value);if(json.bytes(data)>MAX_PART_BYTES)throw new ExportFailure("PART_LIMIT");
            return new ExportTask.Result(data,started,Instant.now(),false);
        }
        if(bearer==null||!bearer.startsWith("Bearer ")||bearer.length()>8192)throw new ExportFailure("AUTHORIZATION_REQUIRED");
        try {
            return client.get().uri(URI.create(urls.get(task.kind().service)+task.kind().path(task.parent(),task.detail(),task.cursor())))
                .header("Authorization",bearer).header(RequestBudget.HEADER,Long.toString(RequestBudget.forCall(3000).epochMillis())).exchange((request,response)->{
                    int status=response.getStatusCode().value();
                    if(status==404&&task.kind().optional()){
                        requireOrder(owner,task.parent());
                        return new ExportTask.Result("{\"missing\":true}",started,Instant.now(),true);
                    }
                    if(status!=200)throw new ExportFailure(status==401||status==403?"AUTHORIZATION_REQUIRED":status==404?"SOURCE_CHANGED":"SOURCE_UNAVAILABLE");
                    var type=response.getHeaders().getContentType();
                    if(type==null||!(task.kind().pdf()?MediaType.APPLICATION_PDF.isCompatibleWith(type):MediaType.APPLICATION_JSON.isCompatibleWith(type)))throw new ExportFailure("SOURCE_FORMAT");
                    int limit=task.kind().pdf()?262144:MAX_PART_BYTES;
                    byte[] bytes=response.getBody().readNBytes(limit+1);if(bytes.length>limit)throw new ExportFailure("PART_LIMIT");
                    String data;
                    if(task.kind().pdf()){
                        if(bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new ExportFailure("SOURCE_FORMAT");
                        data=json.write(Map.of("mediaType","application/pdf","representation","rendered-stored-document","base64",Base64.getEncoder().encodeToString(bytes)));
                    }else data=json.write(json.parse(bytes));
                    if(json.bytes(data)>MAX_PART_BYTES)throw new ExportFailure("PART_LIMIT");
                    return new ExportTask.Result(data,started,Instant.now(),false);
                });
        }catch(ExportFailure failure){throw failure;}catch(RestClientException failure){throw new ExportFailure("SOURCE_UNAVAILABLE");}
    }
    private void requireOrder(CustomerPrincipal owner,long id){
        try{
            orderProof.get().uri("/internal/orders/{id}/access",id).header(RequestBudget.HEADER,Long.toString(RequestBudget.forCall(3000).epochMillis())).exchange((request,response)->{
                if(response.getStatusCode().value()!=200)throw new ExportFailure("SOURCE_UNAVAILABLE");
                byte[] bytes=response.getBody().readNBytes(4097);if(bytes.length>4096)throw new ExportFailure("SOURCE_FORMAT");
                var proof=json.parse(bytes).path("customerId");
                if(!proof.isIntegralNumber()||!proof.canConvertToLong()||!owner.customerId().equals(proof.longValue()))throw new ExportFailure("SOURCE_CHANGED");
                return true;
            });
        }catch(ExportFailure failure){throw failure;}catch(RestClientException unavailable){throw new ExportFailure("SOURCE_UNAVAILABLE");}
    }
}
