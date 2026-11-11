package org.demo.platformsecurity.privacy;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Only Order supplies expectations derived from its current locked purchase.
 * No account identity, caller proof or free text is accepted by this protocol. */
public final class InventoryRetentionProtocol {
    private InventoryRetentionProtocol() {}
    public static final String PATH="/internal/privacy/commerce-retention/inventory-evidence";
    public static final int REQUEST_LIMIT=8192,REPLY_LIMIT=2048;
    private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public enum Terminal { CONFIRMED, RELEASED }
    public enum Code { OBSERVED, STALE, QUARANTINED, ACTIVE, REVIEW_REQUIRED, MISSING_MILESTONE, MISSING_REDACTION }
    public record Line(Long productId,Integer quantity) {}
    public record Command(Long orderId,UUID generation,Long orderVersion,Phase phase,Long bindingEpoch,Long sourceRevision,
                          Long reservationId,Terminal status,List<Line> lines) {
        public Command { if(lines!=null)lines=Collections.unmodifiableList(new ArrayList<>(lines)); }
        public boolean valid(){return orderId!=null && orderId>0 && generation!=null && orderVersion!=null && orderVersion>=0
                && phase!=null && bindingEpoch!=null && bindingEpoch>0 && sourceRevision!=null && sourceRevision>=0
                && reservationId!=null && reservationId>0 && status!=null && lines!=null && !lines.isEmpty() && lines.size()<=100
                && lines.stream().allMatch(l->l!=null && l.productId()!=null && l.productId()>0 && l.quantity()!=null && l.quantity()>0)
                && lines.stream().map(Line::productId).distinct().count()==lines.size();}
        public String expectationHash(){
            if(!valid())throw new IllegalArgumentException("Invalid inventory evidence request");
            var raw=new StringBuilder().append(reservationId).append('|').append(status);
            lines.stream().sorted(Comparator.comparing(Line::productId)).forEach(l->raw.append('|').append(l.productId()).append(':').append(l.quantity()));
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("INVENTORY_EVIDENCE_HASH_UNAVAILABLE");}
        }
    }
    public record Reply(long orderId,UUID generation,long orderVersion,Phase phase,long bindingEpoch,long sourceRevision,
                        String expectationHash,State state,Code code,Instant reconciledAt) {}
    public static Reply validateReply(Reply r,Command c){
        require(r!=null && c!=null && c.valid() && r.state()!=null && r.code()!=null
            && r.equals(reply(c,r.state(),r.code(),r.reconciledAt())));
        require((r.state()==State.CONFIRMED)==(r.code()==Code.OBSERVED) && (r.state()==State.CONFIRMED)==(r.reconciledAt()!=null));
        require((r.state()==State.BLOCKED)==(r.code()==Code.ACTIVE));
        if(r.reconciledAt()!=null)require(c.phase()==Phase.REDACTION && !r.reconciledAt().isAfter(Instant.now()) && r.reconciledAt().getNano()%1000==0);
        return r;
    }
    public static Reply reply(Command c,State state,Code code,Instant time){
        return new Reply(c.orderId(),c.generation(),c.orderVersion(),c.phase(),c.bindingEpoch(),c.sourceRevision(),c.expectationHash(),state,code,time);
    }
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("Invalid inventory evidence protocol");}
    private static long integer(JsonNode d,String key){require(d.path(key).isIntegralNumber() && d.path(key).canConvertToLong());return d.path(key).longValue();}
    private static String string(JsonNode d,String key){require(d.path(key).isTextual());return d.path(key).asText();}
    public static Command parseCommand(byte[] raw){
        require(raw.length<=REQUEST_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==9 && d.path("lines").isArray() && d.path("lines").size()<=100);
        List<Line> lines=new ArrayList<>();for(var l:d.path("lines")){require(l.isObject() && l.size()==2);long quantity=integer(l,"quantity");require(quantity>0 && quantity<=Integer.MAX_VALUE);lines.add(new Line(integer(l,"productId"),(int)quantity));}
        var command=new Command(integer(d,"orderId"),UUID.fromString(string(d,"generation")),integer(d,"orderVersion"),Phase.valueOf(string(d,"phase")),integer(d,"bindingEpoch"),integer(d,"sourceRevision"),integer(d,"reservationId"),Terminal.valueOf(string(d,"status")),lines);
        require(command.valid());return command;
    }
    public static Reply parseReply(byte[] raw,Command c){
        require(raw.length<=REPLY_LIMIT && c.valid());var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==10);
        var state=State.valueOf(string(d,"state"));var code=Code.valueOf(string(d,"code"));Instant time=null;
        if(d.path("reconciledAt").isTextual())time=Instant.parse(d.path("reconciledAt").asText());else require(d.has("reconciledAt") && d.path("reconciledAt").isNull());
        require(integer(d,"orderId")==c.orderId() && integer(d,"orderVersion")==c.orderVersion() && integer(d,"bindingEpoch")==c.bindingEpoch()
            && integer(d,"sourceRevision")==c.sourceRevision() && string(d,"generation").equals(c.generation().toString())
            && string(d,"phase").equals(c.phase().name()) && string(d,"expectationHash").equals(c.expectationHash()));
        require((state==State.CONFIRMED)==(code==Code.OBSERVED) && (state==State.CONFIRMED)==(time!=null));
        require((state==State.BLOCKED)==(code==Code.ACTIVE));
        if(time!=null)require(c.phase()==Phase.REDACTION && !time.isAfter(Instant.now()) && time.getNano()%1000==0);
        return reply(c,state,code,time);
    }
}
