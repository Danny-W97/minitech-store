package org.demo.apigateway.config;

import com.sun.net.httpserver.HttpServer;
import org.demo.apigateway.proxy.*;
import org.demo.platformsecurity.BoundedHttpRequestFactory;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class GatewayRetryWireTest {
    private HttpServer server;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger calls=new AtomicInteger();
    @AfterEach void close() { if(server!=null)server.stop(0);workers.shutdownNow(); }
    private String start(int failCount,int failureStatus) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
        server.createContext("/",exchange -> {
            int code=calls.incrementAndGet()<=failCount ? failureStatus : 200;
            byte[] bytes="{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });server.start();return "http://127.0.0.1:"+server.getAddress().getPort();
    }
    private GatewayProxy proxy() {
        var config=new ResilienceConfig();
        var executor=new ResilientHttpExecutor(config.circuitBreakerRegistry(config.circuitBreakerConfig(20,5,50,10,2)),
                config.retryRegistry(config.retryConfig(3,1,2)),config.bulkheadRegistry(config.bulkheadConfig(10,0)));
        return new GatewayProxy(RestClient.builder().requestFactory(new BoundedHttpRequestFactory(500,1000)),executor);
    }
    @Test void transientReadFailuresAreRetriedUntilTheThirdAttemptSucceeds() throws Exception {
        var response=proxy().forward("retry-read",start(2,503),new MockHttpServletRequest("GET","/api/v1/products"),null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(calls.get()).isEqualTo(3);
    }
    @Test void anExhaustedReadReturnsFailureAfterExactlyThreeAttempts() throws Exception {
        String url=start(10,503);var gateway=proxy();
        assertThatThrownBy(() -> gateway.forward("retry-exhausted",url,new MockHttpServletRequest("GET","/api/v1/products"),null)).isInstanceOf(GatewayUnavailableException.class);
        assertThat(calls.get()).isEqualTo(3);
    }
    @Test void aPostThatMayHaveCommittedIsNeverAutomaticallyRepeated() throws Exception {
        String url=start(1,503);var gateway=proxy();
        assertThatThrownBy(() -> gateway.forward("write",url,new MockHttpServletRequest("POST","/api/v1/orders"),"{}".getBytes())).isInstanceOf(GatewayUnavailableException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
