package org.demo.platformsecurity;

import com.sun.net.httpserver.HttpServer;
import org.demo.platformsecurity.privacy.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

class CommerceRetentionHttpTest {
    HttpServer issuer;CommerceRetentionSourceOutbox source;MockMvc mvc;
    final UUID generation=UUID.randomUUID();final String path=CommerceRetentionBindingProtocol.PATH;
    String command(){return "{\"orderId\":17,\"generation\":\""+generation+"\",\"orderVersion\":4,\"phase\":\"REDACTION\",\"expectedEpoch\":0}";}
    @BeforeEach void setup()throws Exception{
        source=mock(CommerceRetentionSourceOutbox.class);when(source.bind(any(),any(),anyLong())).thenReturn(new CommerceRetentionSourceOutbox.Binding(new Subject(17,0,4,generation),Phase.REDACTION,1,0,false));
        issuer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);issuer.createContext("/internal/auth/introspect",e->{
            assertThat(e.getRequestHeaders().getFirst("X-Service-Token")).isEqualTo("fixture-source-own");
            var data=tools.jackson.databind.json.JsonMapper.builder().build().readTree(e.getRequestBody().readAllBytes());String token=data.path("token").asText();
            Actor actor=switch(token){case "order"->new Actor("SERVICE","order-service",null,null);case "returns"->new Actor("SERVICE","returns-service",null,null);case "customer"->new Actor("CUSTOMER",null,17L,null);case "operator"->new Actor("OPERATOR",null,null,null);default->Actor.ANONYMOUS;};
            byte[] body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(actor);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });issuer.start();
        var filter=new PlatformSecurityConfiguration().platformAuthenticationFilter("returns-service","http://127.0.0.1:"+issuer.getAddress().getPort(),"fixture-source-own").getFilter();
        mvc=MockMvcBuilders.standaloneSetup(new CommerceRetentionSourceController(source)).setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver()).addFilters(filter).build();
    }
    @AfterEach void stop(){issuer.stop(0);}
    @Test void realIntrospectionFilterAndControllerAcceptOnlyOrder()throws Exception{
        mvc.perform(post(path).header("Authorization","Bearer order").contentType(MediaType.APPLICATION_JSON).content(command())).andExpect(status().isOk()).andExpect(jsonPath("$.bindingEpoch").value(1)).andExpect(jsonPath("$.sourceRevision").value(0)).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        verify(source).bind(new Subject(17,0,4,generation),Phase.REDACTION,0);
        for(String token:List.of("returns","customer","operator"))mvc.perform(post(path).header("Authorization","Bearer "+token).header("X-Actor-Role","SERVICE").contentType(MediaType.APPLICATION_JSON).content(command())).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Service-Name","order-service").contentType(MediaType.APPLICATION_JSON).content(command())).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer order")).andExpect(status().isForbidden());verifyNoMoreInteractions(source);
    }
    @Test void missingScopeFieldsAndConflictNeverCreatePositiveEvidence()throws Exception{
        for(String body:List.of("{}",command().replace(",\"expectedEpoch\":0",""),command().replace("\"orderId\":17","\"orderId\":0"),command().replace("\"orderVersion\":4","\"orderVersion\":-1")))
            mvc.perform(post(path).header("Authorization","Bearer order").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(source);when(source.bind(any(),any(),anyLong())).thenThrow(new IllegalStateException("synthetic-never-echo"));
        mvc.perform(post(path).header("Authorization","Bearer order").contentType(MediaType.APPLICATION_JSON).content(command())).andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-never-echo"))));
    }
    @Test void bothDirectionsHaveNarrowRouteAuthorities(){
        String invalidate=CommerceRetentionInvalidationClient.PATH;
        for(String name:List.of("returns-service","payment-service","inventory-service","promotion-service","delivery-service","billing-service","notification-service")){
            Actor actor=new Actor("SERVICE",name,null,null);assertThat(RoutePolicy.allowed("POST",invalidate,actor)).isTrue();assertThat(RoutePolicy.allowed("POST",path,actor)).isFalse();
        }
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("CUSTOMER",null,17L,null),new Actor("OPERATOR",null,null,null),new Actor("SERVICE",null,null,null),new Actor("SERVICE","customer-service",null,null)))
            for(String route:List.of(path,invalidate))assertThat(RoutePolicy.allowed("POST",route,actor)).isFalse();
        assertThat(RoutePolicy.allowed("POST",path,new Actor("SERVICE","order-service",null,null))).isTrue();
    }
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(CommerceRetentionSourceConfiguration.class).withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).withBean(ServiceAuthenticationInterceptor.class,()->new ServiceAuthenticationInterceptor("fixture"));}
    @Test void sourceAndPublisherAreAbsentByDefaultAndPublisherCannotStartAlone(){
        context().run(c->{assertThat(c).doesNotHaveBean(CommerceRetentionSourceController.class);assertThat(c).doesNotHaveBean(CommerceRetentionSourceConfiguration.SourcePublisher.class);});
        context().withPropertyValues("privacy.commerce-retention-source.publisher-enabled=true").run(c->assertThat(c).doesNotHaveBean(CommerceRetentionSourceOutbox.class));
        context().withPropertyValues("privacy.commerce-retention-source.enabled=true").run(c->{assertThat(c).hasSingleBean(CommerceRetentionSourceController.class);assertThat(c).doesNotHaveBean(CommerceRetentionSourceConfiguration.SourcePublisher.class);});
    }
    @Test void explicitPublisherRequiresCredentialAndValidDelay(){
        context().withPropertyValues("privacy.commerce-retention-source.enabled=true","privacy.commerce-retention-source.publisher-enabled=true").run(c->assertThat(c).hasFailed());
        context().withPropertyValues("privacy.commerce-retention-source.enabled=true","privacy.commerce-retention-source.publisher-enabled=true","PLATFORM_SERVICE_TOKEN=fixture","privacy.commerce-retention-source.publish-delay-ms=60000")
                .run(c->assertThat(c).hasSingleBean(CommerceRetentionSourceConfiguration.SourcePublisher.class));
        assertThatThrownBy(()->new CommerceRetentionSourceConfiguration.SourcePublisher(source,mock(CommerceRetentionInvalidationClient.class),0)).isInstanceOf(IllegalArgumentException.class);
    }
}
