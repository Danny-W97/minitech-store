package org.demo.platformsecurity.privacy;

import com.sun.net.httpserver.HttpServer;
import org.demo.platformsecurity.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.*;
import static org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.PATH;
import org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.Code;

class PeerRetentionGuardProtocolTest {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    final Command command=new Command(new PeerRetentionProtocol.Scope(Participant.NOTIFICATION,10,UUID.randomUUID(),0,Phase.REDACTION,2,4),POLICY,UUID.randomUUID());
    HttpServer server;
    @AfterEach void close(){if(server!=null)server.stop(0);RequestContextHolder.resetRequestAttributes();}
    byte[] bytes(Object value){return json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);}
    Reply observed(){return reply(command,Code.OBSERVED,Instant.now(),unknown());}
    @Test void knownPreservationBlockIsValidButNeverClearanceOrAStaleObservation(){
        var states=new EnumMap<Guard,State>(Guard.class);states.putAll(unknown());states.put(Guard.NO_PRESERVATION_HOLD,State.BLOCKED);
        assertThat(parseReply(bytes(reply(command,Code.OBSERVED,Instant.now(),states)),command).states().get(Guard.NO_PRESERVATION_HOLD)).isEqualTo(State.BLOCKED);
        assertThatThrownBy(()->validate(reply(command,Code.STALE,Instant.now(),states),command)).isInstanceOf(IllegalArgumentException.class);
        states.put(Guard.NO_PRESERVATION_HOLD,State.CONFIRMED);assertThatThrownBy(()->validate(reply(command,Code.OBSERVED,Instant.now(),states),command)).isInstanceOf(IllegalArgumentException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Participant.class,names={"ORDER"},mode=org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void sevenGuardSourcesUseExactScopeWithoutBroadeningBusinessEvidence(Participant source){
        var scope=new PeerRetentionProtocol.Scope(source,10,UUID.randomUUID(),0,Phase.REDACTION,2,4);
        var c=new Command(scope,POLICY,UUID.randomUUID());assertThat(parseCommand(bytes(c))).isEqualTo(c);
        assertThat(parseReply(bytes(reply(c,Code.OBSERVED,Instant.now(),unknown())),c).states().values()).containsOnly(State.UNKNOWN);
        assertThat(scope.valid()).isEqualTo(source!=Participant.INVENTORY);
        var positive=new EnumMap<Guard,State>(Guard.class);positive.putAll(unknown());positive.put(Guard.NO_ACTIVE_RETRY_OR_DLQ,State.CONFIRMED);
        assertThatThrownBy(()->validate(reply(c,Code.OBSERVED,Instant.now(),positive),c)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictCommandsRejectCoercionDuplicatesExtraFieldsAndForeignPolicies(){
        String good=json.writeValueAsString(command);assertThat(parseCommand(bytes(command))).isEqualTo(command);
        for(String raw:List.of(good.replace("\"orderId\":10","\"orderId\":10.0"),good.replace("\"orderId\":10","\"orderId\":10,\"orderId\":10"),good.replace(POLICY,"unreviewed"),good.replace("NOTIFICATION","ORDER"),good.replace("\"policy\"","\"extra\":true,\"policy\""),good+"{}","x".repeat(REQUEST_LIMIT+1)))
            assertThatThrownBy(()->parseCommand(raw.getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
    }
    @Test void positiveMissingAndInventedGuardsAreRejected(){
        var positive=new EnumMap<Guard,State>(Guard.class);positive.putAll(unknown());positive.put(Guard.NO_ACTIVE_RETRY_OR_DLQ,State.CONFIRMED);
        assertThatThrownBy(()->validate(reply(command,Code.OBSERVED,Instant.now(),positive),command)).isInstanceOf(IllegalArgumentException.class);
        for(String raw:List.of(json.writeValueAsString(observed()).replace("UNKNOWN","CONFIRMED"),json.writeValueAsString(observed()).replace("NO_PRESERVATION_HOLD","FIELD_SCOPE_REVIEWED"),json.writeValueAsString(observed())+"{}","x".repeat(REPLY_LIMIT+1)))
            assertThatThrownBy(()->parseReply(raw.getBytes(StandardCharsets.UTF_8),command)).isInstanceOf(RuntimeException.class);
    }
    @Test void changedChallengeScopePolicyAndExpiredObservationCannotBeReplayed(){
        var r=observed();
        for(Reply bad:List.of(new Reply(r.scope(),r.policy(),UUID.randomUUID(),r.code(),r.observedAt(),r.states()),
                new Reply(r.scope(),"old-policy",r.challenge(),r.code(),r.observedAt(),r.states()),
                reply(command,Code.OBSERVED,Instant.now().minusSeconds(60),unknown()),reply(command,Code.OBSERVED,Instant.now().plusSeconds(60),unknown())))
            assertThatThrownBy(()->validate(bad,command)).isInstanceOf(IllegalArgumentException.class);
        var other=new Command(new PeerRetentionProtocol.Scope(Participant.PAYMENT,10,command.scope().generation(),0,Phase.REDACTION,2,4),POLICY,command.challenge());
        assertThatThrownBy(()->validate(r,other)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void onlyOrderPostMayReachTheEndpoint(){
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("CUSTOMER",null,17L,null),new Actor("OPERATOR",null,null,null),new Actor("SERVICE","delivery-service",null,null))){
            assertThat(RoutePolicy.allowed("POST",PATH,actor)).isFalse();
            var request=new MockHttpServletRequest();request.setAttribute(Access.ACTOR,actor);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertThatThrownBy(()->new PeerRetentionConfiguration.Controller(mock(PeerRetentionEvidence.class)).guards(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        var order=new Actor("SERVICE","order-service",null,null);
        assertThat(RoutePolicy.allowed("POST",PATH,order)).isTrue();assertThat(RoutePolicy.allowed("GET",PATH,order)).isFalse();
    }
    @Test void controllerBoundsInputAndMarksResponsesPrivateNoStore(){
        var local=mock(PeerRetentionEvidence.class);var controller=new PeerRetentionConfiguration.Controller(local);
        var request=new MockHttpServletRequest();request.setAttribute(Access.ACTOR,new Actor("SERVICE","order-service",null,null));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        request.setContent(new byte[REQUEST_LIMIT+1]);assertThatThrownBy(()->controller.guards(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(local);
        request.setContent(bytes(command));when(local.guards(command)).thenReturn(observed());var response=controller.guards(request);
        assertThat(response.getHeaders().getCacheControl()).contains("private","no-store");assertThat(response.getBody().states().values()).containsOnly(State.UNKNOWN);
    }
    @Test void httpClientUsesAuthenticatedBoundedGuardProtocol()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PATH,e->{assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-token");var c=parseCommand(e.getRequestBody().readAllBytes());byte[] raw=bytes(reply(c,Code.OBSERVED,Instant.now(),unknown()));e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});server.start();
        var client=new PeerRetentionClient("http://127.0.0.1:"+server.getAddress().getPort(),new ServiceAuthenticationInterceptor("fixture-token"));
        assertThat(client.guards(command).states().values()).containsOnly(State.UNKNOWN);
    }
    @Test void malformedHttpAndRedirectsReturnSanitizedUnavailableErrors()throws Exception{
        var count=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PATH,e->{count.incrementAndGet();e.getResponseHeaders().set("Location","/other");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/other",e->{count.addAndGet(100);e.sendResponseHeaders(200,-1);e.close();});server.start();
        var client=new PeerRetentionClient("http://127.0.0.1:"+server.getAddress().getPort(),new ServiceAuthenticationInterceptor("fixture-token"));
        assertThatThrownBy(()->client.guards(command)).hasMessage("PEER_GUARDS_UNAVAILABLE").hasNoCause();assertThat(count.get()).isEqualTo(1);
        server.removeContext(PATH);server.createContext(PATH,e->{byte[] raw="fixture-private-content".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});
        assertThatThrownBy(()->client.guards(command)).hasMessage("PEER_GUARDS_UNAVAILABLE").hasNoCause();
    }
}
