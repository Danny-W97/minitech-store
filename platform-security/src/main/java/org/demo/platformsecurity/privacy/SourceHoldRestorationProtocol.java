package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

/** A scoped negative-hold write. Replies never contain the signed case authorization. */
public final class SourceHoldRestorationProtocol {
    private SourceHoldRestorationProtocol(){}
    public static final String PATH="/internal/privacy/commerce-retention/source-hold-restoration";
    public static final int REQUEST_LIMIT=8192,REPLY_LIMIT=2048;
    public enum Mode { RESTORE, REPLAY_ONLY }
    public enum Code { RESTORED, REPLAYED, AUTHORIZATION_REQUIRED, EXPIRED_AUTHORIZATION, UNKNOWN_PURCHASE,
        QUARANTINED, STALE_SOURCE, CONFLICT, REPLAY_UNAVAILABLE }
    public record Command(SourceHoldDeliveryProtocol.Command delivery,UUID intentId,UUID caseReference,
                          UUID holdId,long bindingEpoch,long sourceRevision,UUID challenge,String authorization,Mode mode){
        public boolean valid(){return delivery!=null && delivery.valid() && intentId!=null && caseReference!=null && holdId!=null && bindingEpoch>0 && sourceRevision>=0 && challenge!=null && authorization!=null && authorization.length()<=4096 && mode!=null;}
        @Override public String toString(){return "SourceHoldRestorationCommand[intentId="+intentId+",source="+(delivery==null?null:delivery.source())+",authorization=[REDACTED]]";}
    }
    public record Reply(String scopeHash,UUID challenge,UUID intentId,CommerceRetentionAssessment.Participant source,
                        Code code,Instant observedAt,long bindingEpoch,long sourceRevision,UUID caseAuthorizationId){}
    private static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static String scopeHash(Command c){
        require(c!=null && c.valid());var d=c.delivery();return Access.hash("A43.40/hold-restoration/1\n"+d.source()+"\n"+d.orderId()+"\n"+d.requestId()+"\n"+d.guard()+"\n"+d.reason()+"\n"+c.intentId()+"\n"+c.caseReference()+"\n"+c.holdId()+"\n"+c.bindingEpoch()+"\n"+c.sourceRevision()+"\n");
    }
    public static Reply reply(Command c,Code code,long epoch,long revision,UUID caseAuthorization){return new Reply(scopeHash(c),c.challenge(),c.intentId(),c.delivery().source(),code,Instant.now(),epoch,revision,caseAuthorization);}
    public static Reply validate(Reply r,Command c){
        require(c!=null && c.valid() && r!=null && scopeHash(c).equals(r.scopeHash()) && c.challenge().equals(r.challenge()) && c.intentId().equals(r.intentId()) && c.delivery().source()==r.source() && r.code()!=null);
        require(r.observedAt()!=null && !r.observedAt().isBefore(Instant.now().minusSeconds(15)) && !r.observedAt().isAfter(Instant.now().plusSeconds(5)));
        boolean success=r.code()==Code.RESTORED || r.code()==Code.REPLAYED;
        require(success?r.bindingEpoch()==c.bindingEpoch() && r.sourceRevision()>c.sourceRevision() && r.caseAuthorizationId()!=null:r.bindingEpoch()==0 && r.sourceRevision()==0 && r.caseAuthorizationId()==null);
        if(success)require(SourceHoldCaseAuthorization.unverifiedClaimedId(c).equals(r.caseAuthorizationId()));return r;
    }
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException("Invalid source hold restoration");}
    private static String text(JsonNode d,String key){require(d.path(key).isTextual());return d.path(key).asText();}
    private static long number(JsonNode d,String key){require(d.path(key).isIntegralNumber() && d.path(key).canConvertToLong());return d.path(key).longValue();}
    public static Command parseCommand(byte[] raw){
        require(raw!=null && raw.length<=REQUEST_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==9);
        var c=new Command(SourceHoldDeliveryProtocol.parseCommand(JSON.writeValueAsBytes(d.path("delivery"))),UUID.fromString(text(d,"intentId")),UUID.fromString(text(d,"caseReference")),UUID.fromString(text(d,"holdId")),number(d,"bindingEpoch"),number(d,"sourceRevision"),UUID.fromString(text(d,"challenge")),text(d,"authorization"),Mode.valueOf(text(d,"mode")));require(c.valid());return c;
    }
    public static Reply parseReply(byte[] raw,Command c){
        require(raw!=null && raw.length<=REPLY_LIMIT);var d=JSON.readTree(raw);require(d!=null && d.isObject() && d.size()==9 && d.has("caseAuthorizationId"));
        return validate(new Reply(text(d,"scopeHash"),UUID.fromString(text(d,"challenge")),UUID.fromString(text(d,"intentId")),CommerceRetentionAssessment.Participant.valueOf(text(d,"source")),Code.valueOf(text(d,"code")),Instant.parse(text(d,"observedAt")),number(d,"bindingEpoch"),number(d,"sourceRevision"),d.path("caseAuthorizationId").isNull()?null:UUID.fromString(text(d,"caseAuthorizationId"))),c);
    }
}
