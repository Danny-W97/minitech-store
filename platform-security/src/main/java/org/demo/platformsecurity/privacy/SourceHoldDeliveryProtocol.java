package org.demo.platformsecurity.privacy;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Durable negative registration acknowledgement only, never retention clearance. */
public final class SourceHoldDeliveryProtocol {
    private SourceHoldDeliveryProtocol(){}
    public static final String PATH="/internal/privacy/commerce-retention/source-holds";
    public static final int REQUEST_LIMIT=2048,REPLY_LIMIT=2048;
    public enum Code { RECORDED, UNKNOWN_PURCHASE, CONFLICT, QUARANTINED }
    public record Command(Participant source,long orderId,UUID requestId,Guard guard,CommerceRetentionSourceHolds.Reason reason){
        public boolean valid(){return source!=null && CommerceRetentionSourceHolds.SOURCES.contains(source) && orderId>0 && requestId!=null && guard!=null && CommerceRetentionSourceHolds.GUARDS.contains(guard) && reason!=null;}
    }
    public record Reply(Command command,Code code,UUID holdId){}
    private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static Reply validate(Reply r,Command c){
        require(c!=null && c.valid() && r!=null && c.equals(r.command()) && r.code()!=null);
        require((r.code()==Code.RECORDED)==(r.holdId()!=null));return r;
    }
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException("Invalid source hold delivery");}
    private static String text(JsonNode d,String key){require(d.path(key).isTextual());return d.path(key).asText();}
    private static Command command(JsonNode d){
        require(d!=null && d.isObject() && d.size()==5 && d.path("orderId").isIntegralNumber() && d.path("orderId").canConvertToLong());
        var c=new Command(Participant.valueOf(text(d,"source")),d.path("orderId").longValue(),UUID.fromString(text(d,"requestId")),Guard.valueOf(text(d,"guard")),CommerceRetentionSourceHolds.Reason.valueOf(text(d,"reason")));require(c.valid());return c;
    }
    public static Command parseCommand(byte[] raw){require(raw!=null && raw.length<=REQUEST_LIMIT);return command(JSON.readTree(raw));}
    public static Reply parseReply(byte[] raw,Command expected){
        require(raw!=null && raw.length<=REPLY_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==3 && d.has("holdId"));
        UUID hold=d.path("holdId").isNull()?null:UUID.fromString(text(d,"holdId"));return validate(new Reply(command(d.path("command")),Code.valueOf(text(d,"code")),hold),expected);
    }
}
