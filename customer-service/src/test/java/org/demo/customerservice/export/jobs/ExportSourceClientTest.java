package org.demo.customerservice.export.jobs;

import com.sun.net.httpserver.HttpServer;
import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.customerservice.export.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportSourceClientTest {
    HttpServer server;ExportSourceClient client;List<String> auth=new ArrayList<>(),paths=new ArrayList<>();
    AtomicInteger status=new AtomicInteger(200);AtomicReference<String> body=new AtomicReference<>("{\"safe\":true}");
    AtomicReference<String> type=new AtomicReference<>("application/json");
    AtomicInteger delayMillis=new AtomicInteger();
    final CustomerPrincipal owner=new CustomerPrincipal(1L,"owner@fixture.example");
    @BeforeEach void setup()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{synchronized(auth){auth.add(exchange.getRequestHeaders().getFirst("Authorization"));paths.add(exchange.getRequestURI().toString());}byte[] data;
            if(exchange.getRequestURI().getPath().startsWith("/internal/")){data="{\"customerId\":1,\"guestAccessTokenHash\":null}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,data.length);}
            else{data=body.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type.get());exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/private");exchange.sendResponseHeaders(status.get(),data.length);}
            try{Thread.sleep(delayMillis.get());exchange.getResponseBody().write(data);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var env=new MockEnvironment().withProperty("PLATFORM_SERVICE_TOKEN","service-only-proof");for(String name:List.of("order","payment","billing","wishlist"))env.withProperty(name.toUpperCase()+"_SERVICE_URL","http://127.0.0.1:"+server.getAddress().getPort());
        client=new ExportSourceClient(env,new ExportJson(),mock(AccountDataExportService.class),mock(SecurityDataExportService.class));
    }
    @AfterEach void close(){server.stop(0);}
    ExportTask task(ExportKind kind){return new ExportTask(UUID.randomUUID(),UUID.randomUUID(),kind,7,3,0,UUID.randomUUID());}
    @Test void forwardsCurrentCustomerOnlyToFixedExportPath(){var result=client.fetch(task(ExportKind.WISHLIST),owner,"Bearer current-customer");assertThat(result.data()).isEqualTo("{\"safe\":true}");assertThat(auth).containsExactly("Bearer current-customer");assertThat(paths).containsExactly("/wishlists/me/data-export?size=50&cursor=0");}
    @Test void optional404RequiresIndependentOrderOwnerProof(){status.set(404);var result=client.fetch(task(ExportKind.PAYMENT),owner,"Bearer current-customer");assertThat(result.missing()).isTrue();assertThat(auth).containsExactly("Bearer current-customer","Bearer service-only-proof");assertThat(paths).containsExactly("/payments/me/data-export/orders/7","/internal/orders/7/access");assertThatThrownBy(()->client.fetch(task(ExportKind.PAYMENT),new CustomerPrincipal(2L,"other@fixture.example"),"Bearer other")).isInstanceOfSatisfying(ExportFailure.class,e->assertThat(e.code).isEqualTo("SOURCE_CHANGED"));}
    @Test void required404AndRedirectAreErrorsNotEmptySuccess(){for(int value:List.of(404,302,503)){status.set(value);assertThatThrownBy(()->client.fetch(task(ExportKind.WISHLIST),owner,"Bearer current")).isInstanceOf(ExportFailure.class);}assertThat(paths).hasSize(3);assertThat(auth).allMatch(a->a.equals("Bearer current"));}
    @Test void capsResponseBeforeParsingAndRejectsDuplicateFields(){body.set("x".repeat(524289));assertThatThrownBy(()->client.fetch(task(ExportKind.WISHLIST),owner,"Bearer current")).isInstanceOfSatisfying(ExportFailure.class,e->assertThat(e.code).isEqualTo("PART_LIMIT"));body.set("{\"safe\":1,\"safe\":2}");assertThatThrownBy(()->client.fetch(task(ExportKind.WISHLIST),owner,"Bearer current")).isInstanceOfSatisfying(ExportFailure.class,e->assertThat(e.code).isEqualTo("SOURCE_FORMAT"));}
    @Test void pdfIsBoundedAndMustBeTheRequestedRepresentation(){type.set("application/pdf");body.set("%PDF-fixture");var data=client.fetch(task(ExportKind.CORRECTION_PDF),owner,"Bearer current");assertThat(data.data()).contains("base64","rendered-stored-document");assertThat(paths).containsExactly("/invoices/me/data-export/orders/7/corrections/3/pdf");body.set("%PDF-"+"x".repeat(262144));assertThatThrownBy(()->client.fetch(task(ExportKind.CORRECTION_PDF),owner,"Bearer current")).isInstanceOf(ExportFailure.class);}
    @Test void rejectsUntrustedOriginsAndInvalidBearerWithoutRequests(){var env=new MockEnvironment().withProperty("ORDER_SERVICE_URL","http://user:password@localhost/private");assertThatThrownBy(()->new ExportSourceClient(env,new ExportJson(),null,null)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->client.fetch(task(ExportKind.WISHLIST),owner,"bad")).isInstanceOf(ExportFailure.class);assertThat(paths).isEmpty();}
    @Test void currentDeadlineAlsoBoundsAResponseBodyThatStalls(){delayMillis.set(500);long started=System.nanoTime();try(var budget=org.demo.platformsecurity.RequestBudget.open(100)){assertThatThrownBy(()->client.fetch(task(ExportKind.WISHLIST),owner,"Bearer current")).isInstanceOf(ExportFailure.class);}assertThat(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()).isLessThan(2000);}
}
