package org.demo.platformsecurity.privacy;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** One reviewed bounded SQL policy for normal cleanup and isolated replay. */
public final class CoreCleanupSql {
    private CoreCleanupSql(){}
    private static String resource(String name){try(var input=CoreCleanupSql.class.getResourceAsStream("/privacy/"+name)){if(input==null)throw new IllegalStateException("Cleanup resource missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);}catch(java.io.IOException error){throw new IllegalStateException("Cleanup resource unavailable");}}
    public static String query(String owner){if(!Set.of("wishlist","review","recommendation").contains(owner))throw new IllegalArgumentException("Invalid cleanup owner");return resource(owner+"-cleanup.sql");}
    public record Step(String sql,List<String> parameterNames,boolean terminal){
        public Object[] parameters(long customer){String raw=Long.toString(customer);String standard=Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.US_ASCII)),url=Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));return parameterNames.stream().map(name->switch(name){case "customer"->customer;case "standard"->standard;case "standardUnpadded"->standard.replace("=","");case "url"->url;case "urlUnpadded"->url.replace("=","");default->throw new IllegalStateException("Invalid cleanup parameter");}).toArray();}
    }
    private static final List<Step> STEPS=load();
    private static List<Step> load(){var data=tools.jackson.databind.json.JsonMapper.builder().build().readTree(resource("customer-cleanup.json"));var result=new ArrayList<Step>();for(var step:data){var names=new ArrayList<String>();for(var name:step.path("parameters"))names.add(name.asText());result.add(new Step(step.path("sql").asText(),List.copyOf(names),step.path("terminal").asBoolean()));}return List.copyOf(result);}
    public static List<Step> customerSteps(){return STEPS;}
}
