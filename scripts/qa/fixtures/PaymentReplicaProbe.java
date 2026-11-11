package org.demo.qa;

import org.demo.paymentservice.PaymentServiceApplication;
import org.demo.paymentservice.payment.PaymentGateway;
import org.demo.paymentservice.payment.PaymentProcessingException;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Compiled ONLY by the isolated QA runner. This adapter is absent from shipped JARs. */
@Configuration(proxyBeanMethods=false)
public class PaymentReplicaProbe {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{PaymentServiceApplication.class,PaymentReplicaProbe.class},args);
    }
    @Bean @Primary PaymentGateway fixtureGateway() {
        var mapper=new ObjectMapper();
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var endpoint=URI.create(System.getenv("A08_FIXTURE_URL")+"/authorize");
        if (!"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("Loopback fixture required");
        return new PaymentGateway() {
            public String provider() { return "fixture"; }
            public java.util.Set<org.demo.paymentservice.payment.PaymentMethod> supportedMethods() {
                return java.util.Set.of(org.demo.paymentservice.payment.PaymentMethod.CARD,
                        org.demo.paymentservice.payment.PaymentMethod.CASH_ON_DELIVERY,
                        org.demo.paymentservice.payment.PaymentMethod.CASH_AT_PICKUP);
            }
            public void requireAvailable() {}
            public Authorization authorize(Long order,java.math.BigDecimal amount,String currency,
                    org.demo.paymentservice.payment.PaymentMethod method,String key,boolean failure) {
                try {
                    var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
                        .header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "orderId",order,"amount",amount,"currency",currency,"method",method.name(),"key",key,"failure",failure)))).build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode()!=200) throw new IllegalStateException("Fixture rejected authorization");
                    return mapper.readValue(response.body(),Authorization.class);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();throw new PaymentProcessingException("Fixture interrupted",error);
                } catch (Exception error) { throw new PaymentProcessingException("Fixture response lost",error); }
            }
        };
    }
}
