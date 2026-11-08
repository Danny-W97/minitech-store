package org.demo.orderservice.privacy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Domain-separated, length-prefixed trusted metadata; no dependence on map/set iteration or timezone. */
final class HoldReviewFingerprint {
    private HoldReviewFingerprint(){}
    static String hash(String domain,Object... values){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);write(out,domain);for(var v:values)write(out,v);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid review fingerprint metadata");}
    }
    private static byte[] encoded(Object v)throws Exception{var b=new ByteArrayOutputStream();write(new DataOutputStream(b),v);return b.toByteArray();}
    private static void write(DataOutputStream out,Object v)throws Exception{
        if(v==null){out.writeByte(0);return;}
        if(v instanceof Timestamp t){write(out,t.toInstant());return;}
        if(v instanceof Instant t){out.writeByte(1);text(out,t.toString());return;}
        if(v instanceof UUID id){out.writeByte(2);text(out,id.toString());return;}
        if(v instanceof Enum<?> e){out.writeByte(3);text(out,e.getDeclaringClass().getName());text(out,e.name());return;}
        if(v instanceof String s){out.writeByte(4);text(out,s);return;}
        if(v instanceof Boolean b){out.writeByte(5);out.writeBoolean(b);return;}
        if(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long){out.writeByte(6);out.writeLong(((Number)v).longValue());return;}
        if(v instanceof Map<?,?> m){out.writeByte(7);var keys=m.keySet().stream().map(k->{if(!(k instanceof String))throw new IllegalArgumentException();return (String)k;}).sorted().toList();out.writeInt(keys.size());for(var key:keys){text(out,key);write(out,m.get(key));}return;}
        if(v instanceof Set<?> set){out.writeByte(8);var items=new ArrayList<byte[]>();for(var item:set)items.add(encoded(item));items.sort(Arrays::compareUnsigned);out.writeInt(items.size());for(var item:items){out.writeInt(item.length);out.write(item);}return;}
        if(v instanceof List<?> list){out.writeByte(9);out.writeInt(list.size());for(var item:list)write(out,item);return;}
        if(v instanceof Record record){out.writeByte(10);text(out,record.getClass().getName());var fields=record.getClass().getRecordComponents();out.writeInt(fields.length);for(var field:fields){text(out,field.getName());write(out,field.getAccessor().invoke(record));}return;}
        throw new IllegalArgumentException();
    }
    private static void text(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
}
