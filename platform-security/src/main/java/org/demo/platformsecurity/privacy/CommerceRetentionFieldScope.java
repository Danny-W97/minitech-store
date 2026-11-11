package org.demo.platformsecurity.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reviewed D02 field plan only. Does not attest deployed schemas, preservation,
 * process completion, independent journal freshness or permission to mutate. */
public final class CommerceRetentionFieldScope {
    public static final String RESOURCE="/privacy/commerce-retention-field-scope.json";
    public static final String SHA256="46fc1c016c9cf8c712572936201a28f14ddf5598da1460898cc3551ca3dba5bb";
    private static final int LIMIT=65536;
    private CommerceRetentionFieldScope() {}
    public static String verifiedDigest(){
        try(var input=CommerceRetentionFieldScope.class.getResourceAsStream(RESOURCE)){
            if(input==null)throw rejected();return verify(input.readNBytes(LIMIT+1));
        }catch(IOException failure){throw rejected();}
    }
    static String verify(byte[] raw){
        if(raw==null || raw.length==0 || raw.length>LIMIT)throw rejected();
        // Normalize checkout line endings only; every definition byte is pinned.
        byte[] normalized=new String(raw,StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        try{
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
            if(!SHA256.equals(actual))throw rejected();return actual;
        }catch(NoSuchAlgorithmException impossible){throw rejected();}
    }
    private static IllegalStateException rejected(){return new IllegalStateException("RETENTION_FIELD_SCOPE_UNAVAILABLE");}
}
