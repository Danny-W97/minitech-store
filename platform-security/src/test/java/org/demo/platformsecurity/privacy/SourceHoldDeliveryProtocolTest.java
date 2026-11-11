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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.SourceHoldDeliveryProtocol.*;
import static org.demo.platformsecurity.privacy.SourceHoldDeliveryProtocol.PATH;
import org.demo.platformsecurity.privacy.SourceHoldDeliveryProtocol.Code;

class SourceHoldDeliveryProtocolTest {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();HttpServer server;
    Command command(Participant p){return new Command(p,10,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionSourceHolds.Reason.PRESERVATION_REQUEST);}
    byte[] bytes(Object value){return json.writeValueAsBytes(value);}
    @AfterEach void close(){if(server!=null)server.stop(0);RequestContextHolder.resetRequestAttributes();}
    @ParameterizedTest @EnumSource(value=Participant.class,names={"ORDER"},mode=EnumSource.Mode.EXCLUDE)
    void exactNegativeAcknowledgementRoundTripsForSevenSources(Participant source){
        var c=command(source);assertThat(parseCommand(bytes(c))).isEqualTo(c);var r=new Reply(c,Code.RECORDED,UUID.randomUUID());assertThat(parseReply(bytes(r),c)).isEqualTo(r);
        assertThat(parseReply(bytes(new Reply(c,Code.UNKNOWN_PURCHASE,null)),c).holdId()).isNull();
    }
    @Test void strictPayloadRejectsCoercionDuplicatesUnknownFieldsAndInvalidGuards(){
        var c=command(Participant.PAYMENT);var raw=json.writeValueAsString(c);
        for(String bad:List.of(raw.replace("\"orderId\":10","\"orderId\":10.0"),raw.replace("\"orderId\":10","\"orderId\":10,\"orderId\":10"),raw.replace("PAYMENT","ORDER"),raw.replace("NO_PRESERVATION_HOLD","INDEPENDENT_JOURNAL_CURRENT"),raw.replace("\"reason\"","\"extra\":true,\"reason\""),raw+"{}","x".repeat(REQUEST_LIMIT+1)))assertThatThrownBy(()->parseCommand(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
    }
    @Test void replyCannotBorrowAnotherRequestOrInventARecordedOutcome(){
        var c=command(Participant.PAYMENT);var other=command(Participant.INVENTORY);
        for(Reply r:List.of(new Reply(other,Code.RECORDED,UUID.randomUUID()),new Reply(c,Code.RECORDED,null),new Reply(c,Code.CONFLICT,UUID.randomUUID())))assertThatThrownBy(()->validate(r,c)).isInstanceOf(IllegalArgumentException.class);
        var good=json.writeValueAsString(new Reply(c,Code.RECORDED,UUID.randomUUID()));
        for(String bad:List.of(good+"{}",good.replace("RECORDED","CONFIRMED"),good.replace("\"holdId\"","\"extra\":1,\"holdId\""),"x".repeat(REPLY_LIMIT+1)))assertThatThrownBy(()->parseReply(bad.getBytes(StandardCharsets.UTF_8),c)).isInstanceOf(RuntimeException.class);
    }
    @Test void onlyOrderMayPostAndBoundsPrecedeSourceWrites(){
        var local=mock(SourceHoldDeliveryReceiver.class);var controller=new SourceHoldDeliveryConfiguration.Controller(local);var request=new MockHttpServletRequest();
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("CUSTOMER",null,17L,null),new Actor("OPERATOR",null,null,null),new Actor("SERVICE","payment-service",null,null))){
            assertThat(RoutePolicy.allowed("POST",PATH,actor)).isFalse();request.setAttribute(Access.ACTOR,actor);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));assertThatThrownBy(()->controller.deliver(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        var actor=new Actor("SERVICE","order-service",null,null);assertThat(RoutePolicy.allowed("POST",PATH,actor)).isTrue();assertThat(RoutePolicy.allowed("GET",PATH,actor)).isFalse();request.setAttribute(Access.ACTOR,actor);
        request.setContent(new byte[REQUEST_LIMIT+1]);assertThatThrownBy(()->controller.deliver(request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(local);
        var c=command(Participant.PAYMENT);request.setContent(bytes(c));when(local.deliver(c)).thenReturn(new Reply(c,Code.RECORDED,UUID.randomUUID()));assertThat(controller.deliver(request).getHeaders().getCacheControl()).contains("no-store","private");
    }
    @Test void sourceEndpointIsSeparatelyDisabledByDefaultAndRejectsUnsupportedService(){
        var runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SourceHoldDeliveryConfiguration.class)).withBean(DataSource.class,()->mock(DataSource.class)).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->mock(org.springframework.transaction.PlatformTransactionManager.class));
        runner.withPropertyValues("spring.application.name=payment-service").run(c->assertThat(c).doesNotHaveBean(SourceHoldDeliveryConfiguration.Controller.class));
        runner.withPropertyValues("spring.application.name=payment-service","privacy.commerce-retention-source-hold-delivery.enabled=true").run(c->assertThat(c).hasSingleBean(SourceHoldDeliveryConfiguration.Controller.class));
        runner.withPropertyValues("spring.application.name=order-service","privacy.commerce-retention-source-hold-delivery.enabled=true").run(c->assertThat(c).hasFailed());
    }
    @Test void clientUsesAuthenticationAndNeverFollowsRedirectOrLeaksResponseBody()throws Exception{
        var calls=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PATH,e->{calls.incrementAndGet();assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-token");var c=parseCommand(e.getRequestBody().readNBytes(REQUEST_LIMIT+1));var raw=bytes(new Reply(c,Code.RECORDED,UUID.randomUUID()));e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});server.start();
        var client=new PeerRetentionClient("http://127.0.0.1:"+server.getAddress().getPort(),new ServiceAuthenticationInterceptor("fixture-token"));var c=command(Participant.PAYMENT);assertThat(client.deliverHold(c).code()).isEqualTo(Code.RECORDED);
        server.removeContext(PATH);server.createContext(PATH,e->{calls.incrementAndGet();e.getResponseHeaders().set("Location","/other");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/other",e->{calls.addAndGet(100);e.sendResponseHeaders(200,-1);e.close();});
        assertThatThrownBy(()->client.deliverHold(c)).hasMessage("SOURCE_HOLD_DELIVERY_UNAVAILABLE").hasNoCause();assertThat(calls.get()).isEqualTo(2);
        server.removeContext(PATH);server.createContext(PATH,e->{byte[] raw="fixture-private-content".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});assertThatThrownBy(()->client.deliverHold(c)).hasMessage("SOURCE_HOLD_DELIVERY_UNAVAILABLE").hasNoCause();
    }
}
