package org.demo.paymentservice.payment;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class StripeGatewayContractTest {
    @Test void canonicalRefundMustMatchTheRequestedProviderId() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new StripePaymentGateway("sk_test_fixture","http://127.0.0.1:18070/index.html","https://api.stripe.com",false,builder.build());
        server.expect(requestTo("https://api.stripe.com/v1/refunds/re_expected0001"))
                .andRespond(withSuccess("{\"id\":\"re_different0001\",\"object\":\"refund\",\"amount\":1000,\"currency\":\"pln\",\"payment_intent\":\"pi_test_intent0001\",\"status\":\"succeeded\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.refund("re_expected0001")).isInstanceOf(StripeReviewRequiredException.class)
                .hasMessage("REFUND_IDENTITY_CONFLICT");server.verify();
    }
    @Test void providerRejectedCreationExpiryIsADeclineWithoutAnAuthorizationReference() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new StripePaymentGateway("sk_test_fixture","http://127.0.0.1:18070/index.html","https://api.stripe.com",false,builder.build());
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions")).andRespond(withBadRequest()
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"param\":\"expires_at\",\"type\":\"invalid_request_error\"}}"));
        var result=gateway.authorize(101L,BigDecimal.TEN,"PLN",PaymentMethod.CARD,"same-request-key",Instant.EPOCH);
        assertThat(result.approved()).isFalse();assertThat(result.reference()).isNull();server.verify();
    }
    @Test void repeatedCheckoutCreationUsesIdenticalProviderKeyAndExpiryAndNeverSendsInternalCredentials() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new StripePaymentGateway("sk_test_fixture","http://127.0.0.1:18070/index.html","https://api.stripe.com",false,builder.build());
        var expiry=Instant.parse("2026-10-25T21:00:00Z");var previous=new AtomicReference<String>();
        for(int i=0;i<2;i++) server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().string(containsString("expires_at="+expiry.getEpochSecond())))
                .andExpect(request -> {
                    String key=request.getHeaders().getFirst("Idempotency-Key");
                    if(previous.get()==null) previous.set(key);else assertThat(key).isEqualTo(previous.get());
                    assertThat(request.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().contains("service-token"));
                }).andRespond(withSuccess("{\"id\":\"cs_test_example\",\"url\":\"https://checkout.stripe.com/c/pay/test\"}",MediaType.APPLICATION_JSON));
        gateway.authorize(101L,BigDecimal.TEN,"PLN",PaymentMethod.CARD,"same-request-key",expiry);
        gateway.authorize(101L,BigDecimal.TEN,"PLN",PaymentMethod.CARD,"same-request-key",expiry);
        server.verify();
    }
}
