package org.demo.platformsecurity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OrderCustomerOwnershipTest {
    private final RestClient.Builder builder=RestClient.builder().baseUrl("http://order.fixture")
            .requestInterceptor(new ServiceAuthenticationInterceptor("delivery-fixture-credential"));
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final OrderAccess access=new OrderAccess(builder.build());
    private final Actor owner=new Actor("CUSTOMER",null,17L,"same@fixture.example");
    private void actor(Actor actor) {
        var request=new MockHttpServletRequest();request.setAttribute(Access.ACTOR,actor);
        request.addHeader("Authorization","Bearer caller-secret");request.addHeader(Access.ORDER_TOKEN,"guest-capability");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();server.verify();}
    private void response(String body){server.expect(requestTo("http://order.fixture/internal/orders/99/access"))
            .andExpect(header("Authorization","Bearer delivery-fixture-credential"))
            .andExpect(headerDoesNotExist(Access.ORDER_TOKEN)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));}
    @Test void ownerIsVerifiedWithServiceIdentityAndNoForwardedCustomerOrGuestCredentials(){
        actor(owner);response("{\"customerId\":17,\"customerEmail\":\"different@fixture.example\",\"guestAllowed\":false}");
        assertThat(access.requireCustomerOwner(99L).customerId()).isEqualTo(17L);
    }
    @Test void matchingEmailAndGuestCapabilityNeverMakeAnotherOrderOwned(){
        actor(owner);
        response("{\"customerId\":18,\"customerEmail\":\"same@fixture.example\",\"guestAllowed\":false}");
        response("{\"customerId\":null,\"customerEmail\":\"same@fixture.example\",\"guestAllowed\":true}");
        status(404,()->access.requireCustomerOwner(99L));status(404,()->access.requireCustomerOwner(99L));
    }
    @Test void privilegedAnonymousAndInvalidCustomerActorsAreDeniedBeforeHttp(){
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,17L,"x"),new Actor("SERVICE","delivery-service",17L,"x"),
                new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x"))) {
            actor(actor);status(actor.authenticated()?403:401,()->access.requireCustomerOwner(99L));
        }
    }
    @Test void invalidOrderIdentifiersAreRejectedWithoutHttp(){
        actor(owner);for(Long id:new Long[]{null,0L,-1L})status(404,()->access.requireCustomerOwner(id));
    }
    @Test void missingOrderIsIndistinguishableFromUnownedOrder(){
        actor(owner);server.expect(requestTo("http://order.fixture/internal/orders/99/access")).andRespond(withResourceNotFound());
        status(404,()->access.requireCustomerOwner(99L));
    }
    @Test void sourceAuthenticationAndServerErrorsFailClosedAsUnavailable(){
        actor(owner);
        for(var code:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.INTERNAL_SERVER_ERROR))
            server.expect(requestTo("http://order.fixture/internal/orders/99/access")).andRespond(withStatus(code));
        for(int i=0;i<3;i++)status(503,()->access.requireCustomerOwner(99L));
    }
    @Test void emptyAndMalformedAuthorityResponsesFailClosed(){
        actor(owner);response("");response("invalid-json");
        status(503,()->access.requireCustomerOwner(99L));status(503,()->access.requireCustomerOwner(99L));
    }
    @Test void ownershipIsRecheckedForEveryRequestWithoutCaching(){
        actor(owner);response("{\"customerId\":17,\"customerEmail\":null,\"guestAllowed\":false}");
        response("{\"customerId\":18,\"customerEmail\":null,\"guestAllowed\":false}");
        assertThat(access.requireCustomerOwner(99L).customerId()).isEqualTo(17L);status(404,()->access.requireCustomerOwner(99L));
    }
    private void status(int expected,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
            error->assertThat(error.getStatusCode().value()).isEqualTo(expected));}
}
