package org.demo.platformsecurity.privacy;

import org.junit.jupiter.api.*;
import org.demo.platformsecurity.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.platformsecurity.privacy.PeerRetentionProtocol.*;
import org.demo.platformsecurity.privacy.PeerRetentionProtocol.Code;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

class PeerRetentionProtocolTest {
    final tools.jackson.databind.ObjectMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    Command c(Participant p){return new Command(new Scope(p,10,UUID.randomUUID(),0,Phase.REDACTION,2,4),new Facts(0,"CANCELLED","10.00","10.00","0.00","FIXTURE","TRACK","CANCELLED",3,false,null,0));}
    @Test void strictScopeAndExactMoneyRejectCoercionAndExtraFields(){var c=c(Participant.DELIVERY);String good=json.writeValueAsString(c);assertThat(parseCommand(good.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(c);for(String raw:List.of(good.replace("\"orderId\":10","\"orderId\":10.0"),good.replace("\"customerId\":0","\"customerId\":0,\"email\":\"fixture\""),good.replace("\"amount\":\"10.00\"","\"amount\":10.00"),good.replace("\"amount\":\"10.00\"","\"amount\":\"10\""),good.replace("\"orderId\":10","\"orderId\":10,\"orderId\":10"),good+"{}","x".repeat(8193)))assertThatThrownBy(()->parseCommand(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);}
    @Test void clockStateHashAndParticipantMustAgree(){var c=c(Participant.DELIVERY);var t=Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);var good=reply(c,State.CONFIRMED,Code.OBSERVED,t,null,0);assertThat(parseReply(json.writeValueAsBytes(good),c)).isEqualTo(good);assertThatThrownBy(()->validate(reply(c,State.UNKNOWN,Code.OBSERVED,t,null,0),c)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->validate(reply(c,State.CONFIRMED,Code.OBSERVED,Instant.now().plusSeconds(60),null,0),c)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->validate(new Reply(new Scope(Participant.RETURNS,10,c.scope().generation(),0,Phase.REDACTION,2,4),c.expectationHash(),State.CONFIRMED,Code.OBSERVED,t,null,0),c)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->parseReply((json.writeValueAsString(good)+"{}").getBytes(java.nio.charset.StandardCharsets.UTF_8),c)).isInstanceOf(RuntimeException.class);}
    @Test void canonicalReceiptDigestIsOrderIndependentAndCannotOmitIds(){var a="payment-"+"a".repeat(64);var b="payment-"+"b".repeat(64);assertThat(eventHash(List.of(a,b))).isEqualTo(eventHash(List.of(b,a)));assertThat(eventHash(List.of(a))).isNotEqualTo(eventHash(List.of(a,b)));for(var ids:List.of(List.of(a,a),List.of("payment-v2-fixture"),List.<String>of()))assertThatThrownBy(()->eventHash(ids)).isInstanceOf(IllegalArgumentException.class);}
    @Test void consumerConfirmationMustMatchPaymentDigestAndCount(){var original=c(Participant.BILLING);var f=original.facts();String hash=eventHash(List.of("payment-"+"a".repeat(64)));var c=new Command(original.scope(),new Facts(17,f.status(),f.amount(),f.subtotal(),f.discount(),f.promotionCode(),f.tracking(),f.deliveryStatus(),f.deliveryRevision(),true,hash,1));var t=Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);assertThat(validate(reply(c,State.CONFIRMED,Code.OBSERVED,t,hash,1),c).eventHash()).isEqualTo(hash);assertThatThrownBy(()->validate(reply(c,State.CONFIRMED,Code.OBSERVED,t,"f".repeat(64),1),c)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->validate(reply(c,State.CONFIRMED,Code.OBSERVED,t,hash,2),c)).isInstanceOf(IllegalArgumentException.class);}
    @Test void minimumAndUnsupportedPeersCannotAcquireConfirmation(){var c=c(Participant.DELIVERY);var s=c.scope();var minimum=new Command(new Scope(s.source(),s.orderId(),s.generation(),0,Phase.MINIMUM_REMOVAL,2,4),c.facts());assertThatThrownBy(()->validate(reply(minimum,State.CONFIRMED,Code.OBSERVED,Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS),null,0),minimum)).isInstanceOf(IllegalArgumentException.class);assertThat(new Scope(Participant.ORDER,10,UUID.randomUUID(),0,Phase.REDACTION,1,0).valid()).isFalse();}
    @Test void configurationIsAbsentUnlessExplicitAndSourceMustBeSupported(){var base=new ApplicationContextRunner().withUserConfiguration(PeerRetentionConfiguration.class).withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class));base.run(ctx->{assertThat(ctx).hasNotFailed();assertThat(ctx).doesNotHaveBean(PeerRetentionEvidence.class);assertThat(ctx).doesNotHaveBean(PeerRetentionConfiguration.Controller.class);});base.withPropertyValues("privacy.commerce-retention-peer-evidence.enabled=true","spring.application.name=delivery-service").run(ctx->{assertThat(ctx).hasSingleBean(PeerRetentionEvidence.class);assertThat(ctx).hasSingleBean(PeerRetentionConfiguration.Controller.class);});base.withPropertyValues("privacy.commerce-retention-peer-evidence.enabled=true","spring.application.name=inventory-service").run(ctx->assertThat(ctx).hasFailed());}
    @Test void routeRejectsEveryRoleExceptOrderPost(){String path=PeerRetentionProtocol.PATH;for(var actor:List.of(Actor.ANONYMOUS,new Actor("CUSTOMER",null,17L,null),new Actor("OPERATOR",null,null,null),new Actor("SERVICE","delivery-service",null,null),new Actor("SERVICE",null,null,null)))assertThat(RoutePolicy.allowed("POST",path,actor)).isFalse();var order=new Actor("SERVICE","order-service",null,null);assertThat(RoutePolicy.allowed("POST",path,order)).isTrue();assertThat(RoutePolicy.allowed("GET",path,order)).isFalse();}
    @Test void fenceProtocolEnforcesValidScopeAndActionLifecycle(){
        var token=UUID.randomUUID();
        var scope=new PeerRetentionFenceProtocol.Scope(Participant.DELIVERY,10,UUID.randomUUID(),0,Phase.REDACTION,2,4,token);
        var cmd=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.PREPARE,scope);
        String raw=json.writeValueAsString(cmd);
        assertThat(PeerRetentionFenceProtocol.parseCommand(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(cmd);
        var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var reply=new PeerRetentionFenceProtocol.Reply(scope,PeerRetentionFenceProtocol.Action.PREPARE,PeerRetentionFenceProtocol.Status.FENCED,now,now.plusSeconds(15));
        String replyJson=json.writeValueAsString(reply);
        assertThat(PeerRetentionFenceProtocol.parseReply(replyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8),cmd)).isEqualTo(reply);
        assertThatThrownBy(()->PeerRetentionFenceProtocol.validate(new PeerRetentionFenceProtocol.Reply(scope,PeerRetentionFenceProtocol.Action.PREPARE,PeerRetentionFenceProtocol.Status.FENCED,now,now.minusSeconds(1)),cmd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PeerRetentionFenceProtocol.validate(new PeerRetentionFenceProtocol.Reply(scope,PeerRetentionFenceProtocol.Action.PREPARE,PeerRetentionFenceProtocol.Status.STALE,now,now.plusSeconds(15)),cmd)).isInstanceOf(IllegalArgumentException.class);
        String fencePath=PeerRetentionFenceProtocol.PATH;
        assertThat(RoutePolicy.allowed("POST",fencePath,new Actor("SERVICE","order-service",null,null))).isTrue();
        assertThat(RoutePolicy.allowed("POST",fencePath,new Actor("CUSTOMER",null,17L,null))).isFalse();
    }
}
