package org.demo.platformsecurity.privacy;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/** Scoped, challenged source observations. There is deliberately no positive clearance. */
public final class PeerRetentionGuardProtocol {
    private PeerRetentionGuardProtocol() {}
    public static final String PATH="/internal/privacy/commerce-retention/peer-guards";
    public static final int REQUEST_LIMIT=2048, REPLY_LIMIT=2048;
    public static final Set<Participant> SOURCES=Set.of(Participant.RETURNS,Participant.PAYMENT,Participant.INVENTORY,
        Participant.PROMOTION,Participant.DELIVERY,Participant.BILLING,Participant.NOTIFICATION);
    public static final Set<Guard> GUARDS=Set.of(Guard.NO_PRESERVATION_HOLD,Guard.NO_OPEN_OR_REOPENED_CASE,Guard.NO_ACTIVE_RETRY_OR_DLQ);
    public enum Code { OBSERVED, STALE, QUARANTINED }
    public record Command(PeerRetentionProtocol.Scope scope,String policy,UUID challenge) {
        // Guard observations include Inventory. The separate business-evidence protocol remains six-source.
        public boolean valid(){return scope!=null && scope.source()!=null && SOURCES.contains(scope.source()) && scope.orderId()>0
            && scope.generation()!=null && scope.orderVersion()>=0 && scope.phase()!=null && scope.epoch()>0
            && scope.revision()>=0 && POLICY.equals(policy) && challenge!=null;}
    }
    public record Reply(PeerRetentionProtocol.Scope scope,String policy,UUID challenge,Code code,Instant observedAt,Map<Guard,State> states) {
        public Reply { states=states==null?Map.of():Map.copyOf(states); }
    }
    private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static Reply reply(Command c,Code code,Instant time,Map<Guard,State> states){return new Reply(c.scope(),c.policy(),c.challenge(),code,time,states);}
    public static Map<Guard,State> unknown(){return Map.of(Guard.NO_PRESERVATION_HOLD,State.UNKNOWN,Guard.NO_OPEN_OR_REOPENED_CASE,State.UNKNOWN,Guard.NO_ACTIVE_RETRY_OR_DLQ,State.UNKNOWN);}
    public static Reply validate(Reply r,Command c){
        require(c!=null && c.valid() && r!=null && c.scope().equals(r.scope()) && c.policy().equals(r.policy()) && c.challenge().equals(r.challenge()) && r.code()!=null);
        require(r.observedAt()!=null && !r.observedAt().isBefore(Instant.now().minusSeconds(15)) && !r.observedAt().isAfter(Instant.now().plusSeconds(5)));
        require(r.states().keySet().equals(GUARDS) && r.states().values().stream().allMatch(v->v==State.UNKNOWN || v==State.BLOCKED));
        require(r.code()==Code.OBSERVED || r.states().values().stream().allMatch(v->v==State.UNKNOWN));
        return r;
    }
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("Invalid source guard observation");}
    private static String text(JsonNode d,String key){require(d.path(key).isTextual());return d.path(key).asText();}
    private static long number(JsonNode d,String key){require(d.path(key).isIntegralNumber() && d.path(key).canConvertToLong());return d.path(key).longValue();}
    private static PeerRetentionProtocol.Scope scope(JsonNode d){require(d.isObject() && d.size()==7);return new PeerRetentionProtocol.Scope(Participant.valueOf(text(d,"source")),number(d,"orderId"),UUID.fromString(text(d,"generation")),number(d,"orderVersion"),Phase.valueOf(text(d,"phase")),number(d,"epoch"),number(d,"revision"));}
    public static Command parseCommand(byte[] raw){require(raw!=null && raw.length<=REQUEST_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==3);var c=new Command(scope(d.path("scope")),text(d,"policy"),UUID.fromString(text(d,"challenge")));require(c.valid());return c;}
    public static Reply parseReply(byte[] raw,Command c){
        require(raw!=null && raw.length<=REPLY_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==6);
        var states=d.path("states");require(states.isObject() && states.size()==3);var result=new EnumMap<Guard,State>(Guard.class);
        for(var guard:GUARDS)result.put(guard,State.valueOf(text(states,guard.name())));
        return validate(new Reply(scope(d.path("scope")),text(d,"policy"),UUID.fromString(text(d,"challenge")),Code.valueOf(text(d,"code")),Instant.parse(text(d,"observedAt")),result),c);
    }
}
