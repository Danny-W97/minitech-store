package org.demo.orderservice.delivery;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.demo.orderservice.config.ResilientHttpExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeliveryClientTest {
    private MockRestServiceServer server;
    private DeliveryClient client;
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var executor = new ResilientHttpExecutor(CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()), BulkheadRegistry.ofDefaults());
        client = new DeliveryClient(builder, "http://delivery", executor);
    }

    @Test
    void requestsQuoteWithServerCalculatedBasketAndSelectedDate() {
        server.expect(requestTo("http://delivery/deliveries/quote"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.methodCode").value("STANDARD_COURIER"))
                .andExpect(jsonPath("$.subtotal").value(100.00))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.requestedDate").value("2026-11-02"))
                .andRespond(withSuccess("""
                        {"methodId":1,"methodCode":"STANDARD_COURIER","name":"Courier",
                         "deliveryCost":15.99,"subtotal":100,"totalPrice":115.99,
                         "fulfillmentType":"HOME_DELIVERY","selectedDate":"2026-11-02",
                         "availableDates":["2026-11-02"]}
                        """, MediaType.APPLICATION_JSON));
        var quote = client.quote(" standard_courier ", "00-001", 2, new BigDecimal("100.00"), DATE);
        assertThat(quote.deliveryCost()).isEqualByComparingTo("15.99");
        assertThat(quote.selectedDate()).isEqualTo(DATE);
        server.verify();
    }

    @Test
    void rejectsUnavailableMethodOrDate() {
        server.expect(requestTo("http://delivery/deliveries/quote")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> client.quote("NO_SUCH_METHOD", "00-001", 1, BigDecimal.TEN, DATE))
                .isInstanceOf(InvalidDeliverySelectionException.class);
        server.verify();
    }

    @Test
    void treatsServerFailureAsUnavailableInsteadOfGrantingFreeDelivery() {
        server.expect(requestTo("http://delivery/deliveries/quote")).andRespond(withServerError());
        assertThatThrownBy(() -> client.quote(null, "00-001", 1, BigDecimal.TEN, null))
                .isInstanceOf(DeliveryServiceException.class);
        server.verify();
    }

    @Test
    void rejectsMalformedQuote() {
        server.expect(requestTo("http://delivery/deliveries/quote"))
                .andRespond(withSuccess("{\"methodCode\":\"STANDARD_COURIER\",\"deliveryCost\":-1}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.quote(null, "00-001", 1, BigDecimal.TEN, null))
                .isInstanceOf(DeliveryServiceException.class);
        server.verify();
    }

    @Test
    void forwardsPickupCodeAndReceivesCanonicalLocation() {
        server.expect(requestTo("http://delivery/deliveries/quote"))
                .andExpect(jsonPath("$.pickupLocation").value("WARSAW_STORE"))
                .andRespond(withSuccess("""
                        {"methodCode":"STORE_PICKUP","deliveryCost":0,"fulfillmentType":"STORE_PICKUP",
                         "selectedDate":"2026-11-02","pickupLocationCode":"WARSAW_STORE","pickupLocation":"Server store"}
                        """, MediaType.APPLICATION_JSON));
        var quote = client.quote("STORE_PICKUP", "00-001", 1, BigDecimal.TEN, DATE, "WARSAW_STORE");
        assertThat(quote.pickupLocation()).isEqualTo("Server store");
        server.verify();
    }

    @Test
    void pickupWithoutConfirmedLocationIsAnInvalidQuote() {
        server.expect(requestTo("http://delivery/deliveries/quote"))
                .andRespond(withSuccess("""
                        {"methodCode":"STORE_PICKUP","deliveryCost":0,"fulfillmentType":"STORE_PICKUP","selectedDate":"2026-11-02"}
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.quote("STORE_PICKUP", "00-001", 1, BigDecimal.TEN, DATE, null))
                .isInstanceOf(DeliveryServiceException.class);
        server.verify();
    }
}
