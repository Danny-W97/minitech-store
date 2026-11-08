package org.demo.platformsecurity.privacy;

import com.sun.net.httpserver.HttpServer;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

class CommerceRetentionBindingClientTest {
    HttpServer server;final Subject subject=new Subject(17,23,4,UUID.randomUUID());
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    String reply(){return JsonMapper.builder().build().writeValueAsString(new CommerceRetentionBindingProtocol.Reply(17,subject.chainGeneration(),4,Phase.REDACTION,0,1,7,true));}
    String start(com.sun.net.httpserver.HttpHandler handler)throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext(CommerceRetentionBindingProtocol.PATH,handler);server.start();return "http://127.0.0.1:"+server.getAddress().getPort();}
    CommerceRetentionBindingClient client(String url,String token){return new CommerceRetentionBindingClient(url,new ServiceAuthenticationInterceptor(token));}
    @Test void exactScopeAndOwnServiceCredentialAreUsedWithoutCustomerIdentity()throws Exception{
        var requests=new ArrayList<String>();var credentials=new ArrayList<String>();
        String origin=start(e->{requests.add(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));credentials.add(e.getRequestHeaders().getFirst("Authorization"));byte[] body=reply().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        var result=client(origin,"fixture-order-token").bind(subject,Phase.REDACTION,0);
        assertThat(result.bindingEpoch()).isEqualTo(1);assertThat(result.sourceRevision()).isEqualTo(7);assertThat(result.invalidated()).isTrue();
        assertThat(credentials).containsExactly("Bearer fixture-order-token");var data=JsonMapper.builder().build().readTree(requests.getFirst());assertThat(data.size()).isEqualTo(5);
        assertThat(data.path("orderId").longValue()).isEqualTo(17);assertThat(data.path("expectedEpoch").longValue()).isZero();assertThat(requests.getFirst()).doesNotContain("customer", "email", "proof", "fixture-order-token");
    }
    @Test void malformedOversizedOrMismatchedReceiptsAreRejectedWithoutEchoingPeerContent()throws Exception{
        var good=reply();var bodies=List.of(good.replace("\"orderId\":17","\"orderId\":18"),good.replace("\"bindingEpoch\":1","\"bindingEpoch\":2"),
                good.replace("\"invalidated\":true","\"invalidated\":\"true\""),good.replace("\"sourceRevision\":7","\"sourceRevision\":7.0"),
                good.replace("\"sourceRevision\":7","\"sourceRevision\":9223372036854775808"),good.replace("\"phase\":\"REDACTION\"","\"phase\":\"MINIMUM_REMOVAL\""),
                good.replace("\"invalidated\":true","\"other\":true"),good.replace("\"orderId\":17","\"orderId\":17,\"orderId\":17"),good+"{}", "[]", "synthetic-never-echo", "x".repeat(2049));
        var index=new AtomicInteger();String origin=start(e->{byte[] body=bodies.get(index.getAndIncrement()).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        var client=client(origin,"fixture");for(int n=0;n<bodies.size();n++)assertThatThrownBy(()->client.bind(subject,Phase.REDACTION,0)).hasMessage("RETENTION_BINDING_UNAVAILABLE").hasNoCause();
    }
    @Test void errorsAndRedirectsAreNotFollowed()throws Exception{
        var hits=new AtomicInteger();String origin=start(e->{hits.incrementAndGet();e.getResponseHeaders().set("Location","/redirect-target");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/redirect-target",e->{hits.addAndGet(100);e.sendResponseHeaders(200,-1);e.close();});
        assertThatThrownBy(()->client(origin,"fixture").bind(subject,Phase.REDACTION,0)).hasMessage("RETENTION_BINDING_UNAVAILABLE");assertThat(hits.get()).isEqualTo(1);
    }
    @Test void missingCredentialOrInvalidScopeCannotSendARequest()throws Exception{
        var hits=new AtomicInteger();String origin=start(e->{hits.incrementAndGet();e.sendResponseHeaders(503,-1);e.close();});
        assertThatThrownBy(()->client(origin,"").bind(subject,Phase.REDACTION,0)).hasMessage("RETENTION_BINDING_UNAVAILABLE");
        assertThatThrownBy(()->client(origin,"fixture").bind(subject,Phase.REDACTION,-1)).isInstanceOf(IllegalArgumentException.class);assertThat(hits.get()).isZero();
    }
    @Test void onlyRootHttpOriginsWithoutCredentialsOrParametersAreAccepted(){
        for(String origin:List.of("file:///tmp/fixture","http://user:secret@localhost","http://localhost/path","http://localhost?secret=x","http://localhost#secret","http://localhost:0","http://localhost:65536","http://invalid host"))
            assertThatThrownBy(()->client(origin,"fixture")).hasMessage("Invalid retention binding origin");
    }
}
