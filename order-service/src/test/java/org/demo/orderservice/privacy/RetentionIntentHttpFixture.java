package org.demo.orderservice.privacy;

import com.sun.net.httpserver.*;
import org.demo.platformsecurity.*;
import org.demo.platformsecurity.privacy.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import javax.tools.ToolProvider;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant;

/** Actual controllers and auth filters over loopback; all data belongs to disposable SQL fixtures. */
final class RetentionIntentHttpFixture implements AutoCloseable {
    record Call(Participant source,String path,PeerRetentionFenceProtocol.Command command) {}
    final List<Call> calls=new CopyOnWriteArrayList<>();
    volatile Consumer<Call> afterDispatch=c->{};
    volatile Consumer<Call> beforeResponseBody=c->{};
    volatile Participant losePrepareReply,loseReleaseReply,loseHoldReply,loseRestorationReply;
    private final List<HttpServer> servers=new ArrayList<>();
    private final ExecutorService threads=Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("intent-http-fixture-",0).factory());
    private final Map<Participant,String> origins=new EnumMap<>(Participant.class);
    private final Map<Participant,PeerRetentionClient> clients=new EnumMap<>(Participant.class);
    private InventoryRetentionClient inventory;
    private final JsonMapper json=JsonMapper.builder().build();

    RetentionIntentHttpFixture(Map<Participant,PeerRetentionEvidence> peers,Object inventoryController,Map<Participant,SourceHoldDeliveryReceiver> holdReceivers,Map<Participant,SourceHoldInspector> inspectors,Map<Participant,SourceHoldRestorer> restorers)throws Exception {
        try{
            var issuer=server();
            issuer.createContext("/internal/auth/introspect",e->{
                try{
                    String token=json.readTree(e.getRequestBody().readNBytes(4097)).path("token").asText();
                    Actor actor=switch(token){case "order"->new Actor("SERVICE","order-service",null,null);
                        case "customer"->new Actor("CUSTOMER",null,17L,null);case "operator"->new Actor("OPERATOR",null,null,null);
                        case "other"->new Actor("SERVICE","returns-service",null,null);default->Actor.ANONYMOUS;};
                    byte[] body=json.writeValueAsBytes(actor);e.getResponseHeaders().set("Content-Type","application/json");
                    e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
                }finally{e.close();}
            });issuer.start();
            for(var source:Participant.values()){
                if(source==Participant.ORDER)continue;
                String service=source.name().toLowerCase(Locale.ROOT)+"-service";
                FilterRegistrationBean<?> registration=ReflectionTestUtils.invokeMethod(new PlatformSecurityConfiguration(),
                    "platformAuthenticationFilter",service,"http://127.0.0.1:"+issuer.getAddress().getPort(),"fixture-"+service);
                Object controller=source==Participant.INVENTORY?inventoryController:new PeerRetentionConfiguration.Controller(peers.get(source));
                var mvc=MockMvcBuilders.standaloneSetup(controller,new SourceHoldDeliveryConfiguration.Controller(holdReceivers.get(source)),new SourceHoldInspectionConfiguration.Controller(inspectors.get(source)),new SourceHoldRestorationConfiguration.Controller(restorers.get(source)))
                    .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
                    .addFilters(registration.getFilter()).build();
                var server=server();
                for(String path:List.of(PeerRetentionFenceProtocol.PATH,PeerRetentionGuardProtocol.PATH,SourceHoldDeliveryProtocol.PATH,SourceHoldInspectionProtocol.PATH,SourceHoldRestorationProtocol.PATH,source==Participant.INVENTORY?InventoryRetentionProtocol.PATH:PeerRetentionProtocol.PATH)){
                    server.createContext(path,e->{
                        try{
                            byte[] body=e.getRequestBody().readNBytes(8193);
                            var command=path.equals(PeerRetentionFenceProtocol.PATH)?parseFence(body):null;
                            var call=new Call(source,path,command);calls.add(call);
                            var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(e.getRequestMethod()),path).contentType("application/json").content(body);
                            for(String header:List.of("Authorization","X-Service-Name"))
                                if(e.getRequestHeaders().getFirst(header)!=null)request.header(header,e.getRequestHeaders().getFirst(header));
                            var response=mvc.perform(request).andReturn().getResponse();
                            afterDispatch.accept(call); // Source COMMIT has finished; the coordinator has not received its response.
                            boolean lost=response.getStatus()==200 && (path.equals(SourceHoldDeliveryProtocol.PATH) && source==loseHoldReply || path.equals(SourceHoldRestorationProtocol.PATH) && source==loseRestorationReply
                                || command!=null && (command.action()==PeerRetentionFenceProtocol.Action.PREPARE && source==losePrepareReply
                                    || command.action()==PeerRetentionFenceProtocol.Action.RELEASE && source==loseReleaseReply));
                            byte[] raw=lost?new byte[0]:response.getContentAsByteArray();
                            for(String header:response.getHeaderNames())e.getResponseHeaders().set(header,response.getHeader(header));
                            e.getResponseHeaders().set("Content-Type","application/json");
                            // Deterministic unavailable response after COMMIT, without HTTP client's connection retry heuristics.
                            e.sendResponseHeaders(lost?503:response.getStatus(),raw.length==0?-1:raw.length);
                            beforeResponseBody.accept(call);
                            if(raw.length>0)e.getResponseBody().write(raw);
                        }catch(Exception failure){throw new java.io.IOException("Fixture dispatch failed",failure);}
                        finally{e.close();}
                    });
                }
                server.start();String origin="http://127.0.0.1:"+server.getAddress().getPort();origins.put(source,origin);
                clients.put(source,new PeerRetentionClient(origin,new ServiceAuthenticationInterceptor("order")));
                if(source==Participant.INVENTORY)inventory=new InventoryRetentionClient(origin,new ServiceAuthenticationInterceptor("order"));
            }
        }catch(Exception|Error failure){close();throw failure;}
    }
    private static PeerRetentionFenceProtocol.Command parseFence(byte[] raw){
        try{return PeerRetentionFenceProtocol.parseCommand(raw);}catch(RuntimeException invalid){return null;}
    }
    private HttpServer server()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);servers.add(server);server.setExecutor(threads);return server;
    }
    PeerRetentionProtocol.Reply collect(PeerRetentionProtocol.Command c){return clients.get(c.scope().source()).collect(c);}
    PeerRetentionGuardProtocol.Reply guards(PeerRetentionGuardProtocol.Command c){return clients.get(c.scope().source()).guards(c);}
    SourceHoldDeliveryProtocol.Reply deliverHold(SourceHoldDeliveryProtocol.Command c){return clients.get(c.source()).deliverHold(c);}
    SourceHoldInspectionProtocol.Reply inspectHold(SourceHoldInspectionProtocol.Command c){return clients.get(c.delivery().source()).inspectHold(c);}
    SourceHoldRestorationProtocol.Reply restoreHold(SourceHoldRestorationProtocol.Command c){return clients.get(c.delivery().source()).restoreHold(c);}
    InventoryRetentionProtocol.Reply collect(InventoryRetentionProtocol.Command c){return inventory.collect(c);}
    PeerRetentionFenceProtocol.Reply fence(PeerRetentionFenceProtocol.Command c){return clients.get(c.scope().source()).fence(c);}
    HttpResponse<byte[]> request(Participant source,String token,String method,byte[] raw)throws Exception{
        return request(source,token,method,raw,PeerRetentionFenceProtocol.PATH);
    }
    HttpResponse<byte[]> guardRequest(Participant source,String token,String method,byte[] raw)throws Exception{
        return request(source,token,method,raw,PeerRetentionGuardProtocol.PATH);
    }
    HttpResponse<byte[]> holdRequest(Participant source,String token,String method,byte[] raw)throws Exception{return request(source,token,method,raw,SourceHoldDeliveryProtocol.PATH);}
    HttpResponse<byte[]> inspectionRequest(Participant source,String token,String method,byte[] raw)throws Exception{return request(source,token,method,raw,SourceHoldInspectionProtocol.PATH);}
    HttpResponse<byte[]> restorationRequest(Participant source,String token,String method,byte[] raw)throws Exception{return request(source,token,method,raw,SourceHoldRestorationProtocol.PATH);}
    private HttpResponse<byte[]> request(Participant source,String token,String method,byte[] raw,String path)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(origins.get(source)+path)).timeout(Duration.ofSeconds(5))
            .header("Content-Type","application/json").header("X-Service-Name","order-service")
            .method(method,HttpRequest.BodyPublishers.ofByteArray(raw));
        if(token!=null)request.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()){
            return client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
        }
    }
    @Override public void close(){
        servers.forEach(s->s.stop(0));threads.shutdownNow();
        try{if(!threads.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Fixture workers still running");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }

    /** Compile the actual two Inventory sources, without changing production Maven dependencies. No stale target/classes fallback. */
    static final class InventorySource implements AutoCloseable {
        private final Path temporary=Files.createTempDirectory("a43-inventory-http-");
        private final URLClassLoader loader;
        final Object controller;
        InventorySource(Path root,DataSource ds,org.springframework.transaction.PlatformTransactionManager manager)throws Exception{
            URLClassLoader created=null;
            try{
                var compiler=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(),"JDK compiler required for actual Inventory sources");
                try(var files=compiler.getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
                    var dir=root.resolve("inventory-service/src/main/java/org/demo/inventoryservice/privacy");
                    var units=files.getJavaFileObjectsFromPaths(List.of(dir.resolve("InventoryRetentionEvidence.java"),dir.resolve("InventoryRetentionController.java")));
                    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
                    var options=List.of("-proc:none","--release","25","-classpath",classpath,"-d",temporary.toString());
                    if(!Boolean.TRUE.equals(compiler.getTask(null,files,null,options,null,units).call()))throw new AssertionError("Actual Inventory sources did not compile");
                }
                created=new URLClassLoader(new URL[]{temporary.toUri().toURL()},getClass().getClassLoader());
                var type=created.loadClass("org.demo.inventoryservice.privacy.InventoryRetentionController");
                if(type.getClassLoader()!=created || created.loadClass("org.demo.inventoryservice.privacy.InventoryRetentionEvidence").getClassLoader()!=created)
                    throw new AssertionError("Stale Inventory class on parent classpath");
                controller=type.getConstructor(DataSource.class,org.springframework.transaction.PlatformTransactionManager.class).newInstance(ds,manager);
                loader=created;
            }catch(Exception|Error failure){if(created!=null)created.close();removeTemporary();throw failure;}
        }
        private void removeTemporary()throws Exception{
            try(var paths=Files.walk(temporary)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
        @Override public void close()throws Exception{loader.close();removeTemporary();}
    }
}
