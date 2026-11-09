package org.demo.platformsecurity;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpBudgetTest {
    private HttpServer server;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    @AfterEach void stop() { if(server!=null)server.stop(0); workers.shutdownNow(); }
    private String serve(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/",handler); server.setExecutor(workers); server.start();
        return "http://127.0.0.1:"+server.getAddress().getPort();
    }
    @Test void boundsAResponseThatKeepsTricklingAndPropagatesTheBudget() throws Exception {
        var seen=new java.util.concurrent.atomic.AtomicLong();
        String url=serve(exchange -> {
            seen.set(Long.parseLong(exchange.getRequestHeaders().getFirst(RequestBudget.HEADER)));
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()) {
                for(int i=0;i<200;i++) { out.write('x'); out.flush(); try { Thread.sleep(15); } catch(InterruptedException stopped) { return; } }
            } catch(java.io.IOException disconnected) { /* expected */ }
        });
        var client=RestClient.builder().requestFactory(new BoundedHttpRequestFactory(100,100)).build();
        long start=System.nanoTime();
        try(var ignored=RequestBudget.open(180)) {
            assertThrows(org.springframework.web.client.RestClientException.class,() -> client.get().uri(url).retrieve().body(String.class));
        }
        assertTrue((System.nanoTime()-start)/1_000_000<1200);
        assertTrue(seen.get()>0);
    }
    @Test void boundsStalledHeadersAndDoesNotFollowRedirects() throws Exception {
        String url=serve(exchange -> {
            if(exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/leak"); exchange.sendResponseHeaders(302,-1); exchange.close(); return;
            }
            try { Thread.sleep(2000); } catch(InterruptedException ignored) {}
            exchange.close();
        });
        var client=RestClient.builder().requestFactory(new BoundedHttpRequestFactory(150,150)).build();
        long start=System.nanoTime();
        assertThrows(org.springframework.web.client.RestClientException.class,() -> client.get().uri(url).retrieve().body(String.class));
        assertTrue((System.nanoTime()-start)/1_000_000<1200);
        var redirectClient=RestClient.builder().requestFactory(new BoundedHttpRequestFactory(2000,2000)).build();
        assertEquals(302,redirectClient.get().uri(url+"/redirect").retrieve().toBodilessEntity().getStatusCode().value());
    }
    @Test void supportsPatchAndPreservesRequestBodiesAndHeaders() throws Exception {
        String url=serve(exchange -> {
            assertEquals("PATCH",exchange.getRequestMethod());
            assertEquals("Bearer test",exchange.getRequestHeaders().getFirst("Authorization"));
            var bytes=exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        var client=RestClient.builder().requestFactory(new BoundedHttpRequestFactory(500,1000)).build();
        assertEquals("{\"status\":\"PAID\"}",client.patch().uri(url).header("Authorization","Bearer test")
                .body("{\"status\":\"PAID\"}").retrieve().body(String.class));
    }

    @Test void nestedAndRemoteBudgetsCannotExtendTheLimitAndScopesAreCleaned() {
        try(var parent=RequestBudget.open(1000)) {
            int before=RequestBudget.forCall(10000).remainingMillis();
            try(var child=RequestBudget.open(10000,Long.toString(Long.MAX_VALUE))) {
                assertTrue(RequestBudget.forCall(10000).remainingMillis()<=before);
            }
            assertTrue(RequestBudget.available());
        }
        try(var expired=RequestBudget.open(1000,"1")) { assertFalse(RequestBudget.available()); }
        assertTrue(RequestBudget.available());
    }
}
