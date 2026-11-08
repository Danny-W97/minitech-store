package org.demo.returnsservice.returns;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import java.math.BigDecimal;
import java.util.UUID;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;

class ReturnOrderClientTest {
    @Test void canonicalOwnerAndAvailabilityAreRequiredEvenForOperatorSubmittedIds() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var client=new ReturnOrderClient(builder,"http://order");
        server.expect(requestTo("http://order/orders/501")).andRespond(withSuccess("{\"id\":501,\"customerId\":18,\"customerEmail\":\"other@example.test\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.get(501L,42L,"customer@example.test")).hasMessageContaining("403");server.verify();server.reset();
        server.expect(requestTo("http://order/orders/501")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.get(501L,42L,"customer@example.test")).hasMessageContaining("404");server.verify();server.reset();
        server.expect(requestTo("http://order/orders/501")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.get(501L,42L,"customer@example.test")).hasMessageContaining("503");server.verify();
    }

    @Test void domainEffectClientsUseExpectedRoutesPayloadsAndPreserveCheckoutSessionId() {
        var invBuilder=RestClient.builder();var invServer=MockRestServiceServer.bindTo(invBuilder).build();
        var invClient=new ReturnInventoryClient(invBuilder,"http://localhost:18083");
        UUID opId=UUID.fromString("11111111-2222-3333-4444-555555555555");
        invServer.expect(requestTo("http://localhost:18083/inventory/items/101/receipts"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"productId\":101,\"availableQuantity\":15}",MediaType.APPLICATION_JSON));
        invClient.restock(101L,2,opId);
        invServer.verify();

        var billBuilder=RestClient.builder();var billServer=MockRestServiceServer.bindTo(billBuilder).build();
        var billClient=new ReturnBillingClient(billBuilder,"http://localhost:18095");
        billServer.expect(requestTo("http://localhost:18095/invoices/order/501"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":700,\"orderId\":501,\"invoiceNumber\":\"FV/2026/501\",\"grossAmount\":400.00,\"status\":\"ISSUED\"}",MediaType.APPLICATION_JSON));
        billServer.expect(requestTo("http://localhost:18095/invoices/700/correction-balance"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"invoiceId\":700,\"version\":3,\"grossAmount\":400.00}",MediaType.APPLICATION_JSON));
        billServer.expect(requestTo("http://localhost:18095/invoices/700/corrections"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":801,\"invoiceId\":700,\"correctionNumber\":\"KOR/2026/001\",\"finalGross\":200.00,\"balanceVersion\":4}",MediaType.APPLICATION_JSON));
        assertThat(billClient.byOrder(501L)).isPresent();
        assertThat(billClient.balance(700L).version()).isEqualTo(3L);
        assertThat(billClient.issueCorrection(700L,new BigDecimal("-200.00"),"return-corr-v1-1",3L,"Zwrot #1").correctionNumber())
                .isEqualTo("KOR/2026/001");
        billServer.verify();

        var payBuilder=RestClient.builder();var payServer=MockRestServiceServer.bindTo(payBuilder).build();
        var payClient=new ReturnPaymentClient(payBuilder,"http://localhost:18084");
        payServer.expect(requestTo("http://localhost:18084/payments/order/501"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":900,"orderId":501,"amount":400.00,"currency":"PLN","paymentMethod":"CARD","status":"SUCCEEDED",
                         "providerReference":"pi_test_501","checkoutUrl":"https://checkout.stripe.com/c/pay/cs_test_501",
                         "checkoutSessionId":"cs_test_501"}
                        """,MediaType.APPLICATION_JSON));
        payServer.expect(requestTo("http://localhost:18084/payments/900/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":1001,\"paymentId\":900,\"orderId\":501,\"amount\":200.00,\"currency\":\"PLN\",\"status\":\"SUCCEEDED\",\"providerRefundId\":\"re_1\",\"businessOutcome\":\"REFUNDED_ORDER_REVIEW_REQUIRED\"}",MediaType.APPLICATION_JSON));
        var snap=payClient.byOrder(501L).orElseThrow();
        assertThat(snap.checkoutSessionId()).isEqualTo("cs_test_501");
        assertThat(snap.providerReference()).isEqualTo("pi_test_501");
        var ref=payClient.refund(900L,new BigDecimal("200.00"),"return-ref-v1-1","CUSTOMER_REQUEST");
        assertThat(ref.status()).isEqualTo("SUCCEEDED");
        payServer.verify();
    }
}
