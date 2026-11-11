package org.demo.platformsecurity.privacy;

import com.sun.net.httpserver.HttpServer;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.InventoryRetentionProtocol.*;
import static org.demo.platformsecurity.privacy.InventoryRetentionProtocol.PATH;
import org.demo.platformsecurity.privacy.InventoryRetentionProtocol.Code;

class InventoryRetentionClientTest {
    HttpServer server;final Command command=new Command(17L,UUID.randomUUID(),4L,Phase.REDACTION,2L,7L,13L,Terminal.CONFIRMED,List.of(new Line(1L,2)));
    final Instant clock=Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    String body(){return JsonMapper.builder().build().writeValueAsString(reply(command,State.CONFIRMED,Code.OBSERVED,clock));}
    String start(com.sun.net.httpserver.HttpHandler handler)throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext(PATH,handler);server.start();return "http://127.0.0.1:"+server.getAddress().getPort();}
    @Test void ownCredentialAndExactExpectationAreUsedWithoutAccountData()throws Exception{
        List<String> tokens=new ArrayList<>(),bodies=new ArrayList<>();var url=start(e->{tokens.add(e.getRequestHeaders().getFirst("Authorization"));bodies.add(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] raw=body().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});
        var result=new InventoryRetentionClient(url,new ServiceAuthenticationInterceptor("fixture-order-token")).collect(command);assertThat(result.reconciledAt()).isEqualTo(clock);assertThat(tokens).containsExactly("Bearer fixture-order-token");assertThat(parseCommand(bodies.getFirst().getBytes(StandardCharsets.UTF_8))).isEqualTo(command);assertThat(bodies.getFirst()).doesNotContain("customer","email","fixture-order-token");
    }
    @Test void malformedWrongScopeOversizedAndContradictoryResponsesAreRejected()throws Exception{
        var good=body();var variants=List.of(good.replace("\"orderId\":17","\"orderId\":18"),good.replace("\"bindingEpoch\":2","\"bindingEpoch\":3"),good.replace("\"sourceRevision\":7","\"sourceRevision\":7.0"),good.replace("\"sourceRevision\":7","\"sourceRevision\":9223372036854775808"),good.replace(command.expectationHash(),"0".repeat(64)),good.replace("\"state\":\"CONFIRMED\"","\"state\":\"UNKNOWN\""),good.replace("\"code\":\"OBSERVED\"","\"code\":\"ACTIVE\""),good.replace("\"orderId\":17","\"orderId\":17,\"orderId\":17"),good.replace("\"orderId\":17","\"extra\":17"),good+"{}","[]","fixture-private-error","x".repeat(2049));
        var index=new AtomicInteger();var url=start(e->{byte[] raw=variants.get(index.getAndIncrement()).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});var client=new InventoryRetentionClient(url,new ServiceAuthenticationInterceptor("fixture"));for(int n=0;n<variants.size();n++)assertThatThrownBy(()->client.collect(command)).hasMessage("INVENTORY_EVIDENCE_UNAVAILABLE").hasNoCause();
    }
    @Test void commandParsingIsStrictAndBounded(){
        var json=JsonMapper.builder().build().writeValueAsString(command);for(var raw:List.of(json.replace("\"quantity\":2","\"quantity\":2.0"),json.replace("\"quantity\":2","\"quantity\":2147483648"),json.replace("\"productId\":1","\"productId\":1,\"productId\":1"),json.replace("\"orderId\":17","\"orderId\":17,\"customerId\":1"),json+"{}","x".repeat(8193)))assertThatThrownBy(()->parseCommand(raw.getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        var reversed=new Command(17L,command.generation(),4L,Phase.REDACTION,2L,7L,13L,Terminal.CONFIRMED,List.of(new Line(2L,1),new Line(1L,2)));var sorted=new Command(17L,command.generation(),4L,Phase.REDACTION,2L,7L,13L,Terminal.CONFIRMED,List.of(new Line(1L,2),new Line(2L,1)));assertThat(reversed.expectationHash()).isEqualTo(sorted.expectationHash());
    }
    @Test void invalidClockMinimumAndStateCannotBeConfirmed(){
        for(var time:List.of(Instant.now().plusSeconds(600),clock.plusNanos(1)))assertThatThrownBy(()->validateReply(reply(command,State.CONFIRMED,Code.OBSERVED,time),command)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->validateReply(reply(command,State.CONFIRMED,Code.OBSERVED,null),command)).isInstanceOf(IllegalArgumentException.class);
        var min=new Command(17L,command.generation(),4L,Phase.MINIMUM_REMOVAL,2L,7L,13L,Terminal.CONFIRMED,command.lines());assertThatThrownBy(()->validateReply(reply(min,State.CONFIRMED,Code.OBSERVED,clock),min)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void redirectsAndErrorsCannotExposePeerContent()throws Exception{
        var hits=new AtomicInteger();var url=start(e->{hits.incrementAndGet();e.getResponseHeaders().set("Location","/elsewhere");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/elsewhere",e->{hits.addAndGet(100);e.sendResponseHeaders(200,-1);e.close();});assertThatThrownBy(()->new InventoryRetentionClient(url,new ServiceAuthenticationInterceptor("fixture")).collect(command)).hasMessage("INVENTORY_EVIDENCE_UNAVAILABLE");assertThat(hits.get()).isEqualTo(1);
    }
    @Test void invalidOriginAndScopeCannotSendARequest(){
        for(String url:List.of("file:///fixture","http://user:password@localhost","http://localhost/path","http://localhost?token=fixture","http://localhost#fragment","http://localhost:0","http://localhost:65536"))assertThatThrownBy(()->new InventoryRetentionClient(url,new ServiceAuthenticationInterceptor("fixture"))).isInstanceOf(IllegalArgumentException.class);
        var bad=new Command(17L,command.generation(),4L,Phase.REDACTION,2L,7L,13L,Terminal.CONFIRMED,List.of(new Line(1L,2),new Line(1L,3)));assertThat(bad.valid()).isFalse();assertThatThrownBy(()->new InventoryRetentionClient("http://localhost",new ServiceAuthenticationInterceptor("fixture")).collect(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
