package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.security.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.SourceHoldRestorationProtocol.*;
import org.demo.platformsecurity.privacy.SourceHoldRestorationProtocol.Code;
import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Guard;
import static org.demo.platformsecurity.privacy.SourceHoldRestorationProtocol.PATH;

class SourceHoldRestorationProtocolTest {
    static final KeyPair KEYS;
    static{try{KEYS=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    String publicKey(){return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());}
    Command command(Participant source){return new Command(new SourceHoldDeliveryProtocol.Command(source,10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,3,UUID.randomUUID(),"",Mode.RESTORE);}
    Command token(Command c,Instant issued,long seconds)throws Exception{
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of("authorizationId",UUID.randomUUID().toString(),"scopeHash",scopeHash(c),"issuedAt",issued.toString(),"expiresAt",issued.plusSeconds(seconds).toString())));var signature=Signature.getInstance("Ed25519");signature.initSign(KEYS.getPrivate());signature.update(("A43.40/verified-case/hold-restoration/1\n"+body).getBytes(StandardCharsets.US_ASCII));return new Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),c.bindingEpoch(),c.sourceRevision(),c.challenge(),body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()),c.mode());
    }
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void signedExactScopeRoundTripsWithoutExposingProofInReplyOrLogs(Participant source)throws Exception{
        var c=token(command(source),Instant.now().minusMillis(1),15);var verifier=new SourceHoldCaseAuthorization(publicKey());var a=verifier.verify(c);assertThat(verifier.active(a)).isTrue();assertThat(parseCommand(json.writeValueAsBytes(c))).isEqualTo(c);assertThat(c.toString()).doesNotContain(c.authorization());
        var r=reply(c,Code.RESTORED,2,4,a.id());byte[] raw=json.writeValueAsBytes(r);assertThat(new String(raw,StandardCharsets.UTF_8)).doesNotContain(c.authorization());assertThat(parseReply(raw,c)).isEqualTo(r);
    }
    @Test void signatureAndAllScopeFieldsAreMandatoryWithoutBooleanFixtureBypass()throws Exception{
        var base=command(Participant.PAYMENT);var c=token(base,Instant.now(),15);var verifier=new SourceHoldCaseAuthorization(publicKey());
        assertThatThrownBy(()->new SourceHoldCaseAuthorization("").verify(c)).hasMessage("Scoped case authorization required");assertThatThrownBy(()->verifier.verify(new Command(c.delivery(),UUID.randomUUID(),c.caseReference(),c.holdId(),2,3,c.challenge(),c.authorization(),c.mode()))).hasMessage("Scoped case authorization required");
        assertThatThrownBy(()->verifier.verify(new Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),2,3,c.challenge(),"true",c.mode()))).hasMessage("Scoped case authorization required");
        var stranger=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();assertThatThrownBy(()->new SourceHoldCaseAuthorization(Base64.getEncoder().encodeToString(stranger.getPublic().getEncoded())).verify(c)).hasMessage("Scoped case authorization required");
        assertThatThrownBy(()->verifier.verify(new Command(c.delivery(),c.intentId(),c.caseReference(),UUID.randomUUID(),2,3,c.challenge(),c.authorization(),c.mode()))).hasMessage("Scoped case authorization required");
    }
    @Test void expiryIsDerivedFromSignedTimeAndPastProofIsOnlyEligibleForReceiptLookup()throws Exception{
        Instant now=Instant.parse("2026-11-02T00:00:00Z");var c=token(command(Participant.PAYMENT),now.minusSeconds(30),15);var v=new SourceHoldCaseAuthorization(publicKey(),Clock.fixed(now,ZoneOffset.UTC));assertThat(v.active(v.verify(c))).isFalse();
        var overlong=token(c,now,16);assertThatThrownBy(()->v.verify(overlong)).hasMessage("Scoped case authorization required");var future=token(c,now.plusSeconds(6),15);assertThatThrownBy(()->v.verify(future)).hasMessage("Scoped case authorization required");
    }
    @Test void strictPayloadReplyChallengeBoundsAndVersionCannotBeForged()throws Exception{
        var c=command(Participant.PAYMENT);String raw=json.writeValueAsString(c);
        for(String bad:List.of(raw+"{}",raw.replace("\"bindingEpoch\":2","\"bindingEpoch\":2.0"),raw.replace("\"intentId\"","\"extra\":1,\"intentId\""),"x".repeat(REQUEST_LIMIT+1)))assertThatThrownBy(()->parseCommand(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        var good=reply(c,Code.RESTORED,2,4,UUID.randomUUID());var other=new Command(c.delivery(),c.intentId(),c.caseReference(),c.holdId(),2,3,UUID.randomUUID(),c.authorization(),c.mode());assertThatThrownBy(()->validate(good,other)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->validate(reply(c,Code.RESTORED,2,3,UUID.randomUUID()),c)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->validate(reply(c,Code.CONFLICT,2,4,UUID.randomUUID()),c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parseReply(new byte[REPLY_LIMIT+1],c)).isInstanceOf(RuntimeException.class);
    }
    @Test void onlyOrderCanPostAndMalformedOrOversizedBodyNeverReachesRestorer(){
        var restorer=mock(SourceHoldRestorer.class);var controller=new SourceHoldRestorationConfiguration.Controller(restorer);var request=new MockHttpServletRequest();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("CUSTOMER",null,17L,null),new Actor("OPERATOR",null,null,null),new Actor("SERVICE","payment-service",null,null))){request.setAttribute(Access.ACTOR,actor);assertThat(RoutePolicy.allowed("POST",PATH,actor)).isFalse();assertThatThrownBy(()->controller.restore(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);}
        var actor=new Actor("SERVICE","order-service",null,null);request.setAttribute(Access.ACTOR,actor);assertThat(RoutePolicy.allowed("GET",PATH,actor)).isFalse();assertThat(RoutePolicy.allowed("POST",PATH,actor)).isTrue();request.setContent(new byte[REQUEST_LIMIT+1]);assertThatThrownBy(()->controller.restore(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(restorer);
    }
    @Test void endpointIsDefaultOffAndEnablingRequiresCaseKeyAndSupportedService(){
        var runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SourceHoldRestorationConfiguration.class)).withBean(DataSource.class,()->mock(DataSource.class)).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->mock(org.springframework.transaction.PlatformTransactionManager.class));
        runner.withPropertyValues("spring.application.name=payment-service").run(c->assertThat(c).doesNotHaveBean(SourceHoldRestorationConfiguration.Controller.class));runner.withPropertyValues("spring.application.name=payment-service","privacy.commerce-retention-source-hold-restoration.enabled=true").run(c->assertThat(c).hasFailed());runner.withPropertyValues("spring.application.name=payment-service","privacy.commerce-retention-source-hold-restoration.enabled=true","privacy.commerce-retention-source-hold-restoration.case-public-key="+publicKey()).run(c->assertThat(c).hasSingleBean(SourceHoldRestorationConfiguration.Controller.class));
    }
    @Test void missingCaseAuthorityDeniesBeforeAnySql(){
        var ds=mock(DataSource.class);var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);var c=command(Participant.PAYMENT);var r=new SourceHoldRestorer(Participant.PAYMENT,ds,manager,new SourceHoldCaseAuthorization("")).restore(c);assertThat(r.code()).isEqualTo(Code.AUTHORIZATION_REQUIRED);verifyNoInteractions(ds,manager);
    }
    @Test void clientAuthenticatesNeverRedirectsAndSanitizesResponseContent()throws Exception{
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var calls=new java.util.concurrent.atomic.AtomicInteger();
        try{
            server.createContext(PATH,e->{calls.incrementAndGet();assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-token");var c=parseCommand(e.getRequestBody().readNBytes(REQUEST_LIMIT+1));var raw=json.writeValueAsBytes(reply(c,Code.AUTHORIZATION_REQUIRED,0,0,null));e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});server.start();var client=new PeerRetentionClient("http://127.0.0.1:"+server.getAddress().getPort(),new ServiceAuthenticationInterceptor("fixture-token"));var c=command(Participant.PAYMENT);assertThat(client.restoreHold(c).code()).isEqualTo(Code.AUTHORIZATION_REQUIRED);
            server.removeContext(PATH);server.createContext(PATH,e->{calls.incrementAndGet();e.getResponseHeaders().set("Location","/other");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/other",e->{calls.addAndGet(100);e.sendResponseHeaders(200,-1);e.close();});assertThatThrownBy(()->client.restoreHold(c)).hasMessage("SOURCE_HOLD_RESTORATION_UNAVAILABLE").hasNoCause();assertThat(calls).hasValue(2);
            server.removeContext(PATH);server.createContext(PATH,e->{byte[] raw="fixture-private-response".repeat(REPLY_LIMIT).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,raw.length);try{e.getResponseBody().write(raw);}finally{e.close();}});assertThatThrownBy(()->client.restoreHold(c)).hasMessage("SOURCE_HOLD_RESTORATION_UNAVAILABLE").hasNoCause();
        }finally{server.stop(0);}
    }
}
