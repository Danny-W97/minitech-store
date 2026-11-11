package org.demo.platformsecurity.privacy;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

/** Challenged as-of-source observations only. No repair or preservation clearance. */
public final class SourceHoldInspectionProtocol {
    private SourceHoldInspectionProtocol(){}
    public static final String PATH="/internal/privacy/commerce-retention/source-hold-inspection";
    public static final int REQUEST_LIMIT=2048,REPLY_LIMIT=4096;
    public enum Code { MATCH, MISSING_HOLD, CONFLICT, UNKNOWN_PURCHASE, QUARANTINED }
    public record Command(SourceHoldDeliveryProtocol.Command delivery,UUID expectedHoldId,UUID challenge){
        public boolean valid(){return delivery!=null && delivery.valid() && expectedHoldId!=null && challenge!=null;}
    }
    public record Reply(Command command,Code code,Instant observedAt,long bindingEpoch,long sourceRevision){}
    private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException("Invalid source hold inspection");}
    private static String text(JsonNode d,String k){require(d.path(k).isTextual());return d.path(k).asText();}
    private static long number(JsonNode d,String k){require(d.path(k).isIntegralNumber() && d.path(k).canConvertToLong());return d.path(k).longValue();}
    private static Command command(JsonNode d){
        require(d!=null && d.isObject() && d.size()==3);
        var delivery=SourceHoldDeliveryProtocol.parseCommand(JSON.writeValueAsBytes(d.path("delivery")));
        var c=new Command(delivery,UUID.fromString(text(d,"expectedHoldId")),UUID.fromString(text(d,"challenge")));require(c.valid());return c;
    }
    public static Command parseCommand(byte[] raw){require(raw!=null && raw.length<=REQUEST_LIMIT);return command(JSON.readTree(raw));}
    public static Reply validate(Reply r,Command c){
        require(c!=null && c.valid() && r!=null && c.equals(r.command()) && r.code()!=null);
        require(r.observedAt()!=null && !r.observedAt().isBefore(Instant.now().minusSeconds(15)) && !r.observedAt().isAfter(Instant.now().plusSeconds(5)));
        require(r.code()==Code.UNKNOWN_PURCHASE?r.bindingEpoch()==0 && r.sourceRevision()==0:r.bindingEpoch()>0 && r.sourceRevision()>=0);
        return r;
    }
    public static Reply parseReply(byte[] raw,Command c){
        require(raw!=null && raw.length<=REPLY_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==5);
        return validate(new Reply(command(d.path("command")),Code.valueOf(text(d,"code")),Instant.parse(text(d,"observedAt")),number(d,"bindingEpoch"),number(d,"sourceRevision")),c);
    }
}
