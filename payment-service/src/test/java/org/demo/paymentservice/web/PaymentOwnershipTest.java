package org.demo.paymentservice.web;

import org.demo.paymentservice.payment.PaymentService;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.demo.platformsecurity.OrderAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentOwnershipTest {
    @Test void deniesForeignOrderBeforePaymentOrIdempotencyProcessing() {
        var request = new MockHttpServletRequest();
        request.setAttribute(Access.ACTOR, new Actor("CUSTOMER", null, 17L, "one@example.com"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var builder = RestClient.builder().baseUrl("http://orders");
            var remote = MockRestServiceServer.bindTo(builder).build();
            remote.expect(requestTo("http://orders/internal/orders/42/access")).andRespond(withSuccess(
                    "{\"customerId\":18,\"customerEmail\":\"other@example.com\",\"guestAllowed\":false}", MediaType.APPLICATION_JSON));
            var service = mock(PaymentService.class);
            var controller = new PaymentController(service, new OrderAccess(builder.build()));
            assertThrows(ResponseStatusException.class, () -> controller.create("known-key", new CreatePaymentRequest(42L, "CARD", false)));
            verifyNoInteractions(service);
            remote.verify();
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
