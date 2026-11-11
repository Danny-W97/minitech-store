package org.demo.platformsecurity.privacy;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import org.demo.platformevents.EventIdentity;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Fixed source observations derived from current Order facts, never user proofs. */
public final class PeerRetentionProtocol {
    private PeerRetentionProtocol() {}
    public static final String PATH="/internal/privacy/commerce-retention/peer-evidence";
    public static final int REQUEST_LIMIT=8192,REPLY_LIMIT=2048;
    public static final Set<Participant> PEERS=Set.of(Participant.RETURNS,Participant.PAYMENT,Participant.PROMOTION,Participant.DELIVERY,Participant.BILLING,Participant.NOTIFICATION);
    public enum Code { OBSERVED, STALE, QUARANTINED, ACTIVE, REVIEW_REQUIRED, MISSING_MILESTONE, MISSING_REDACTION, PROVIDER_REVIEW_REQUIRED, RECEIPTS_REQUIRED }
    public record Scope(Participant source,long orderId,UUID generation,long orderVersion,Phase phase,long epoch,long revision){
        public boolean valid(){return source!=null && PEERS.contains(source) && orderId>0 && generation!=null && orderVersion>=0 && phase!=null && epoch>0 && revision>=0;}
    }
    public record Facts(long customerId,String status,String amount,String subtotal,String discount,String promotionCode,
                        String tracking,String deliveryStatus,long deliveryRevision,boolean invoiceRequested,String paymentEventsHash,int paymentEventCount){
        public boolean valid(){return customerId>=0 && Set.of("COMPLETED","CANCELLED").contains(Objects.toString(status,""))
            && money(amount) && money(subtotal) && money(discount) && new java.math.BigDecimal(discount).compareTo(new java.math.BigDecimal(subtotal))<=0
            && (promotionCode==null || !promotionCode.isBlank() && promotionCode.length()<=40)
            && (tracking==null || !tracking.isBlank() && tracking.length()<=60) && (deliveryStatus==null || Set.of("CREATED","IN_TRANSIT","OUT_FOR_DELIVERY","DELIVERED","PICKED_UP","CANCELLED").contains(deliveryStatus))
            && deliveryRevision>=-1 && (paymentEventsHash==null ? paymentEventCount==0 : hash(paymentEventsHash) && paymentEventCount>0 && paymentEventCount<=100);}
    }
    public record Command(Scope scope,Facts facts){public boolean valid(){return scope!=null && scope.valid() && facts!=null && facts.valid();}
        public String expectationHash(){require(valid());return EventIdentity.hash(JSON.writeValueAsString(facts));}}
    public record Reply(Scope scope,String expectationHash,State state,Code code,Instant reconciledAt,String eventHash,int eventCount) {}
    private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static Reply reply(Command c,State state,Code code,Instant time,String eventHash,int eventCount){return new Reply(c.scope(),c.expectationHash(),state,code,time,eventHash,eventCount);}
    public static String eventHash(List<String> ids){require(!ids.isEmpty() && ids.size()<=100 && ids.stream().allMatch(id->id!=null && id.matches("payment-[0-9a-f]{64}")) && new HashSet<>(ids).size()==ids.size());return EventIdentity.hash(JSON.writeValueAsString(ids.stream().sorted().toList()));}
    public static Reply validate(Reply r,Command c){
        require(c!=null && c.valid() && r!=null && c.scope().equals(r.scope()) && c.expectationHash().equals(r.expectationHash()) && r.state()!=null && r.code()!=null);
        require((r.state()==State.CONFIRMED)==(r.code()==Code.OBSERVED) && (r.state()==State.CONFIRMED)==(r.reconciledAt()!=null) && (r.state()==State.BLOCKED)==(r.code()==Code.ACTIVE));
        if(r.reconciledAt()!=null)require(c.scope().phase()==Phase.REDACTION && !r.reconciledAt().isBefore(Instant.EPOCH) && !r.reconciledAt().isAfter(Instant.now()) && r.reconciledAt().getNano()%1000==0);
        boolean events=Set.of(Participant.PAYMENT,Participant.BILLING,Participant.NOTIFICATION).contains(c.scope().source()) && r.state()==State.CONFIRMED;
        require(events ? hash(r.eventHash()) && r.eventCount()>0 && r.eventCount()<=100 : r.eventHash()==null && r.eventCount()==0);
        if(events && c.scope().source()!=Participant.PAYMENT)require(Objects.equals(c.facts().paymentEventsHash(),r.eventHash()) && c.facts().paymentEventCount()==r.eventCount());return r;
    }
    private static boolean money(String v){return v!=null && v.matches("[0-9]{1,17}\\.[0-9]{2}");}
    private static boolean hash(String v){return v!=null && v.matches("[0-9a-f]{64}");}
    private static void require(boolean b){if(!b)throw new IllegalArgumentException("Invalid peer evidence protocol");}
    private static long number(JsonNode d,String k){require(d.path(k).isIntegralNumber() && d.path(k).canConvertToLong());return d.path(k).longValue();}
    private static String text(JsonNode d,String k){require(d.path(k).isTextual());return d.path(k).asText();}
    private static String nullable(JsonNode d,String k){require(d.has(k));return d.path(k).isNull()?null:text(d,k);}
    private static Scope scope(JsonNode d){require(d!=null && d.isObject() && d.size()==7);return new Scope(Participant.valueOf(text(d,"source")),number(d,"orderId"),UUID.fromString(text(d,"generation")),number(d,"orderVersion"),Phase.valueOf(text(d,"phase")),number(d,"epoch"),number(d,"revision"));}
    public static Command parseCommand(byte[] raw){require(raw.length<=REQUEST_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==2);var f=d.path("facts");require(f.isObject() && f.size()==12 && f.path("invoiceRequested").isBoolean());long count=number(f,"paymentEventCount");require(count>=0 && count<=100);
        var c=new Command(scope(d.path("scope")),new Facts(number(f,"customerId"),text(f,"status"),text(f,"amount"),text(f,"subtotal"),text(f,"discount"),nullable(f,"promotionCode"),nullable(f,"tracking"),nullable(f,"deliveryStatus"),number(f,"deliveryRevision"),f.path("invoiceRequested").booleanValue(),nullable(f,"paymentEventsHash"),(int)count));require(c.valid());return c;}
    public static Reply parseReply(byte[] raw,Command c){require(raw.length<=REPLY_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==7);String time=nullable(d,"reconciledAt");long count=number(d,"eventCount");require(count>=0 && count<=100);
        return validate(new Reply(scope(d.path("scope")),text(d,"expectationHash"),State.valueOf(text(d,"state")),Code.valueOf(text(d,"code")),time==null?null:Instant.parse(time),nullable(d,"eventHash"),(int)count),c);}
}
