package org.demo.platformsecurity.privacy;

import com.sun.net.httpserver.HttpServer;
import org.demo.platformsecurity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.SourceHoldInspectionProtocol.*;
import org.demo.platformsecurity.privacy.SourceHoldInspectionProtocol.Code;
import static org.demo.platformsecurity.privacy.SourceHoldInspectionProtocol.PATH;

class SourceHoldInspectionProtocolTest {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();HttpServer server;
    Command command(Participant source){return new Command(new SourceHoldDeliveryProtocol.Command(source,10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST),UUID.randomUUID(),UUID.randomUUID());}
    byte[] bytes(Object value){return json.writeValueAsBytes(value);}
    @AfterEach void close(){if(server!=null)server.stop(0);RequestContextHolder.resetRequestAttributes();}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void sevenSourcesRoundTripAllDiagnosticCodes(Participant source){
        var c=command(source);assertThat(parseCommand(bytes(c))).isEqualTo(c);
        for(var code:Code.values()){var r=new Reply(c,code,Instant.now(),code==Code.UNKNOWN_PURCHASE?0:2,code==Code.UNKNOWN_PURCHASE?0:4);assertThat(parseReply(bytes(r),c)).isEqualTo(r);}
    }
    @Test void strictPayloadRejectsCoercionDuplicatesTrailingExtraFieldsAndBounds(){
        var c=command(Participant.PAYMENT);String raw=json.writeValueAsString(c);
        for(String bad:List.of(raw.replace("\"orderId\":10","\"orderId\":\"10\""),raw.replace("\"orderId\":10","\"orderId\":10,\"orderId\":10"),raw+"{}",raw.replace("PAYMENT","ORDER"),raw.replace("\"challenge\"","\"extra\":1,\"challenge\""),"x".repeat(REQUEST_LIMIT+1)))assertThatThrownBy(()->parseCommand(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        String reply=json.writeValueAsString(new Reply(c,Code.MATCH,Instant.now(),1,0));
        for(String bad:List.of(reply+"{}",reply.replace("\"bindingEpoch\":1","\"bindingEpoch\":1.0"),reply.replace("\"sourceRevision\"","\"extra\":1,\"sourceRevision\""),"x".repeat(REPLY_LIMIT+1)))assertThatThrownBy(()->parseReply(bad.getBytes(StandardCharsets.UTF_8),c)).isInstanceOf(RuntimeException.class);
    }
    @Test void wrongChallengeHoldSourceOrAgeCannotBeReplayedAndVersionsAreStrict(){
        var c=command(Participant.PAYMENT);
        for(Reply bad:List.of(new Reply(new Command(c.delivery(),c.expectedHoldId(),UUID.randomUUID()),Code.MATCH,Instant.now(),1,0),
            new Reply(new Command(c.delivery(),UUID.randomUUID(),c.challenge()),Code.MATCH,Instant.now(),1,0),
            new Reply(command(Participant.INVENTORY),Code.MATCH,Instant.now(),1,0),new Reply(c,Code.MATCH,Instant.now().minusSeconds(30),1,0),
            new Reply(c,Code.MATCH,Instant.now().plusSeconds(30),1,0),new Reply(c,Code.MATCH,Instant.now(),0,0),
            new Reply(c,Code.MATCH,Instant.now(),1,-1),new Reply(c,Code.UNKNOWN_PURCHASE,Instant.now(),1,0)))assertThatThrownBy(()->validate(bad,c)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void onlyOrderMayInspectAndOversizedOrMalformedInputNeverTouchesSql(){
        var inspector=mock(SourceHoldInspector.class);var controller=new SourceHoldInspectionConfiguration.Controller(inspector);var request=new MockHttpServletRequest();
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("CUSTOMER",null,17L,null),new Actor("OPERATOR",null,null,null),new Actor("SERVICE","payment-service",null,null))){
            assertThat(RoutePolicy.allowed("POST",PATH,actor)).isFalse();request.setAttribute(Access.ACTOR,actor);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));assertThatThrownBy(()->controller.inspect(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        var order=new Actor("SERVICE","order-service",null,null);assertThat(RoutePolicy.allowed("POST",PATH,order)).isTrue();assertThat(RoutePolicy.allowed("GET",PATH,order)).isFalse();request.setAttribute(Access.ACTOR,order);
        for(byte[] raw:List.of(new byte[REQUEST_LIMIT+1],"{}".getBytes(StandardCharsets.UTF_8))){request.setContent(raw);assertThatThrownBy(()->controller.inspect(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);}verifyNoInteractions(inspector);
        var c=command(Participant.PAYMENT);request.setContent(bytes(c));when(inspector.inspect(c)).thenReturn(new Reply(c,Code.MATCH,Instant.now(),1,0));assertThat(controller.inspect(request).getHeaders().getCacheControl()).contains("no-store","private");
    }
    @Test void inspectionHasSeparateDefaultOffFlagAndRejectsUnsupportedService(){
        var runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SourceHoldInspectionConfiguration.class)).withBean(DataSource.class,()->mock(DataSource.class)).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->mock(org.springframework.transaction.PlatformTransactionManager.class));
        runner.withPropertyValues("spring.application.name=payment-service","privacy.commerce-retention-source-hold-delivery.enabled=true").run(c->assertThat(c).doesNotHaveBean(SourceHoldInspectionConfiguration.Controller.class));
        runner.withPropertyValues("spring.application.name=payment-service","privacy.commerce-retention-source-hold-inspection.enabled=true").run(c->assertThat(c).hasSingleBean(SourceHoldInspectionConfiguration.Controller.class));
        runner.withPropertyValues("spring.application.name=order-service","privacy.commerce-retention-source-hold-inspection.enabled=true").run(c->assertThat(c).hasFailed());
    }
    @Test void clientAuthenticatesBoundsResponsesAndNeverFollowsRedirectOrLeaksContent()throws Exception{
        var calls=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PATH,e->{calls.incrementAndGet();assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-token");var c=parseCommand(e.getRequestBody().readNBytes(REQUEST_LIMIT+1));var raw=bytes(new Reply(c,Code.MATCH,Instant.now(),1,0));e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});server.start();
        var client=new PeerRetentionClient("http://127.0.0.1:"+server.getAddress().getPort(),new ServiceAuthenticationInterceptor("fixture-token"));var c=command(Participant.PAYMENT);assertThat(client.inspectHold(c).code()).isEqualTo(Code.MATCH);
        server.removeContext(PATH);server.createContext(PATH,e->{calls.incrementAndGet();e.getResponseHeaders().set("Location","/other");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/other",e->{calls.addAndGet(100);e.sendResponseHeaders(200,-1);e.close();});
        assertThatThrownBy(()->client.inspectHold(c)).hasMessage("SOURCE_HOLD_INSPECTION_UNAVAILABLE").hasNoCause();assertThat(calls.get()).isEqualTo(2);
        server.removeContext(PATH);server.createContext(PATH,e->{byte[] raw="fixture-private-content".repeat(REPLY_LIMIT).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,raw.length);try{e.getResponseBody().write(raw);}finally{e.close();}});assertThatThrownBy(()->client.inspectHold(c)).hasMessage("SOURCE_HOLD_INSPECTION_UNAVAILABLE").hasNoCause();
    }
}
