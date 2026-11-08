package org.demo.orderservice.privacy;

import com.sun.net.httpserver.HttpServer;
import org.demo.platformsecurity.*;
import org.demo.platformsecurity.privacy.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;
import static org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol.PATH;

/** Loopback HTTP + real authentication filter/controller; all SQL uses disposable test databases. */
final class RetentionGuardHttpFixture implements AutoCloseable {
    record Wire(int status,byte[] body,Map<String,String> headers) {
        Wire(int status,byte[] body){this(status,body,Map.of());}
    }
    private final List<HttpServer> servers=new ArrayList<>();
    private final ExecutorService threads=Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("guard-http-fixture-",0).factory());
    private final Map<Participant,PeerRetentionClient> clients=new EnumMap<>(Participant.class);
    private final Map<Participant,String> origins=new EnumMap<>(Participant.class);
    private final Map<Participant,List<PeerRetentionGuardProtocol.Command>> commands=new ConcurrentHashMap<>();
    final Map<Participant,UnaryOperator<Wire>> transforms=new ConcurrentHashMap<>();
    volatile Consumer<Participant> beforeDispatch=p->{};
    volatile Consumer<Participant> afterDispatch=p->{};

    RetentionGuardHttpFixture(Map<Participant,PeerRetentionEvidence> peers)throws Exception {
        var json=JsonMapper.builder().build();
        var issuer=server();
        issuer.createContext("/internal/auth/introspect",e->{
            try {
                String token=json.readTree(e.getRequestBody().readAllBytes()).path("token").asText();
                Actor actor=switch(token){
                    case "order"->new Actor("SERVICE","order-service",null,null);
                    case "customer"->new Actor("CUSTOMER",null,17L,null);
                    case "operator"->new Actor("OPERATOR",null,null,null);
                    default->new Actor("SERVICE","inventory-service",null,null);
                };
                byte[] raw=json.writeValueAsBytes(actor);
                e.getResponseHeaders().set("Content-Type","application/json");
                e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);
            }finally{e.close();}
        });
        issuer.start();
        for(var source:PeerRetentionProtocol.PEERS){
            commands.put(source,new CopyOnWriteArrayList<>());
            String service=source.name().toLowerCase(Locale.ROOT)+"-service";
            FilterRegistrationBean<?> registration=ReflectionTestUtils.invokeMethod(new PlatformSecurityConfiguration(),
                    "platformAuthenticationFilter",service,"http://127.0.0.1:"+issuer.getAddress().getPort(),"fixture-"+service);
            var mvc=MockMvcBuilders.standaloneSetup(new PeerRetentionConfiguration.Controller(peers.get(source)))
                    .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
                    .addFilters(registration.getFilter()).build();
            var server=server();
            server.createContext(PATH,e->{
                try {
                    byte[] raw=e.getRequestBody().readNBytes(PeerRetentionGuardProtocol.REQUEST_LIMIT+1);
                    try{commands.get(source).add(PeerRetentionGuardProtocol.parseCommand(raw));}catch(RuntimeException malformed){/* controller owns validation */}
                    beforeDispatch.accept(source);
                    var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                            org.springframework.http.HttpMethod.valueOf(e.getRequestMethod()),PATH)
                            .contentType("application/json").content(raw);
                    for(String header:List.of("Authorization","X-Service-Name"))
                        if(e.getRequestHeaders().getFirst(header)!=null)request.header(header,e.getRequestHeaders().getFirst(header));
                    var response=mvc.perform(request).andReturn().getResponse();
                    var headers=new HashMap<String,String>();
                    for(String header:response.getHeaderNames())headers.put(header,response.getHeader(header));
                    var wire=new Wire(response.getStatus(),response.getContentAsByteArray(),Map.copyOf(headers));
                    afterDispatch.accept(source); // MVC transactions have finished before this hook.
                    wire=transforms.getOrDefault(source,UnaryOperator.identity()).apply(wire);
                    wire.headers().forEach((k,v)->e.getResponseHeaders().set(k,v));
                    e.getResponseHeaders().set("Content-Type","application/json");
                    e.sendResponseHeaders(wire.status(),wire.body().length==0?-1:wire.body().length);
                    if(wire.body().length>0)e.getResponseBody().write(wire.body());
                }catch(Exception failure){throw new java.io.IOException("Fixture HTTP dispatch failed",failure);}
                finally{e.close();}
            });
            server.start();String origin="http://127.0.0.1:"+server.getAddress().getPort();
            origins.put(source,origin);clients.put(source,new PeerRetentionClient(origin,new ServiceAuthenticationInterceptor("order")));
        }
    }
    private HttpServer server()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        servers.add(server);server.setExecutor(threads);return server;
    }
    PeerRetentionClient client(Participant source){return clients.get(source);}
    List<PeerRetentionGuardProtocol.Command> commands(Participant source){return List.copyOf(commands.get(source));}
    HttpResponse<byte[]> request(Participant source,String token,String method,byte[] body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(origins.get(source)+PATH)).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").header("X-Service-Name","order-service")
                .method(method,HttpRequest.BodyPublishers.ofByteArray(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()){
            return client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
        }
    }
    @Override public void close(){
        servers.forEach(s->s.stop(0));threads.shutdownNow();
        try{if(!threads.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Fixture HTTP workers did not stop");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
}
