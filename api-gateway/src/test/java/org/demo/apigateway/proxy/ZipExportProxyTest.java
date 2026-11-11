package org.demo.apigateway.proxy;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.demo.apigateway.config.ResilientHttpExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ZipExportProxyTest {
    private HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    @AfterEach void close() { if (server!=null) server.stop(0); workers.shutdownNow(); }
    private String start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/invoices/export/zip",handler); server.setExecutor(workers); server.start();
        return "http://127.0.0.1:"+server.getAddress().getPort();
    }
    private ZipExportProxy proxy(int timeout) {
        return new ZipExportProxy(RestClient.builder(),new ResilientHttpExecutor(CircuitBreakerRegistry.ofDefaults(),RetryRegistry.ofDefaults(),BulkheadRegistry.ofDefaults()),1,500,timeout);
    }
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("GET","/api/v1/invoices/export/zip");
        request.addHeader("Authorization","Bearer test-operator"); request.setQueryString("from=2026-01-01T00:00:00Z"); return request;
    }
    @Test void forwardsFirstBytesBeforeEndOfArchiveAndHoldsCapacityForTheWholeStream() throws Exception {
        var release=new CountDownLatch(1); var first=new CountDownLatch(1);
        var calls=new AtomicInteger(); var auth=new AtomicReference<String>();
        String url=start(exchange -> {
            calls.incrementAndGet(); auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type","application/zip"); exchange.getResponseHeaders().add("Content-Disposition","attachment; filename=faktury.zip");
            exchange.sendResponseHeaders(200,0);
            try (var out=exchange.getResponseBody()) {
                out.write(new byte[65536]); out.flush();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                out.write(new byte[65536]);
            }
        });
        var response=new MockHttpServletResponse() {
            @Override public jakarta.servlet.ServletOutputStream getOutputStream() {
                var delegate=super.getOutputStream();
                return new jakarta.servlet.ServletOutputStream() {
                    public boolean isReady() { return true; }
                    public void setWriteListener(jakarta.servlet.WriteListener listener) {}
                    public void write(int value) throws java.io.IOException { delegate.write(value); first.countDown(); }
                    public void write(byte[] bytes,int off,int len) throws java.io.IOException { delegate.write(bytes,off,len); first.countDown(); }
                    public void flush() throws java.io.IOException { delegate.flush(); }
                };
            }
        };
        var proxy=proxy(2000);
        var transfer=workers.submit(() -> { proxy.forward(url,request(),response); return null; });
        try {
            assertThat(first.await(3,TimeUnit.SECONDS)).isTrue(); assertThat(transfer.isDone()).isFalse();
            assertThatThrownBy(() -> proxy.forward(url,request(),new MockHttpServletResponse())).hasMessageContaining("429");
        } finally { release.countDown(); }
        transfer.get(5,TimeUnit.SECONDS);
        assertThat(response.getContentAsByteArray()).hasSize(131072); assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getContentType()).isEqualTo("application/zip"); assertThat(auth.get()).isEqualTo("Bearer test-operator"); assertThat(calls.get()).isEqualTo(1);
    }
    @Test void preservesAuthorizationFailuresAndNeverFollowsRedirectsOrRetries503() throws Exception {
        var status=new AtomicInteger(401); var calls=new AtomicInteger();
        String url=start(exchange -> {
            calls.incrementAndGet();
            if (status.get()==302) exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/credential-leak");
            byte[] body="{\"message\":\"Denied\"}".getBytes(); exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),body.length); try(var out=exchange.getResponseBody()) { out.write(body); }
        });
        var proxy=proxy(500); var response=new MockHttpServletResponse(); proxy.forward(url,request(),response);
        assertThat(response.getStatus()).isEqualTo(401); assertThat(response.getContentAsString()).contains("Denied");
        var invalid=request(); invalid.setQueryString("bad=%");
        assertThatThrownBy(() -> proxy.forward(url,invalid,new MockHttpServletResponse())).hasMessageContaining("400");
        for (int code: new int[]{302,503}) {
            status.set(code); assertThatThrownBy(() -> proxy.forward(url,request(),new MockHttpServletResponse())).isInstanceOf(GatewayUnavailableException.class);
        }
        assertThat(calls.get()).isEqualTo(3);
    }
    @Test void stalledBodyAbortsWithoutRetryAndReleasesCapacity() throws Exception {
        var calls=new AtomicInteger(); var release=new CountDownLatch(1);
        String url=start(exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()) {
                out.write(new byte[32768]); out.flush();
                try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        });
        var proxy=proxy(150); var response=new MockHttpServletResponse();
        try {
            assertThatThrownBy(() -> proxy.forward(url,request(),response)).isInstanceOf(java.io.IOException.class);
            assertThat(response.isCommitted()).isTrue(); assertThat(calls.get()).isEqualTo(1);
            assertThatThrownBy(() -> proxy.forward(url,request(),new MockHttpServletResponse())).isNotInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(calls.get()).isEqualTo(2);
        } finally { release.countDown(); }
    }
}
