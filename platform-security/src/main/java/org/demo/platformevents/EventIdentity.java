package org.demo.platformevents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class EventIdentity {
    private EventIdentity() {}
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String payment(String eventKey) { return "payment-"+hash(eventKey); }
    public static Map<String,String> fields(Map<?,?> raw) {
        var fields=new TreeMap<String,String>(); raw.forEach((key,value) -> fields.put(key.toString(),value.toString())); return fields;
    }
    public static String id(String stream,String recordId,Map<String,String> fields) {
        String id=fields.get("eventId");
        if(id==null || id.isBlank()) id=fields.containsKey("eventKey") ? payment(fields.get("eventKey")) : "legacy-"+hash(stream+":"+recordId);
        if(id.length()>128) throw new IllegalArgumentException("Event ID is too long");
        return id;
    }
    public static String fingerprint(Map<String,String> fields) {
        var canonical=new StringBuilder(); new TreeMap<>(fields).forEach((key,value) -> {
            if(!key.equals("eventId")) canonical.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value);
        }); return hash(canonical.toString());
    }
    /** Version 2 keeps the exact v1 business fingerprint and a separately bound opaque ID. */
    public record Envelope(String canonicalId,String aliasId,String payloadHash) {}
    public static Envelope envelope(String suppliedId,Map<String,String> fields) {
        String version=fields.get("protocolVersion");
        if(version==null||version.equals("1"))return new Envelope(suppliedId,null,fingerprint(fields));
        if(!version.equals("2"))throw new IllegalArgumentException("Unsupported event identity version");
        PaymentEventData event=PaymentEventData.parse(fields);
        String alias=fields.get("eventId"),legacyKey=event.type()+":"+event.orderId()+":"+event.recipient();
        if(alias==null||!alias.matches("payment-v2-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")||!alias.equals(suppliedId)
                ||!Objects.equals(fields.get("eventKey"),"v2:"+hash(legacyKey))||!Objects.equals(fields.get("legacyEventId"),payment(legacyKey)))
            throw new IllegalArgumentException("Invalid event identity binding");
        var original=new TreeMap<>(fields);original.remove("protocolVersion");original.remove("legacyEventId");original.put("eventKey",legacyKey);
        return new Envelope(payment(legacyKey),alias,fingerprint(original));
    }
}
