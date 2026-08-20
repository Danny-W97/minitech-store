package org.demo.paymentservice.config;

import org.demo.paymentservice.observability.CorrelationIdClientInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    RestClient.Builder restClientBuilder(
            @Value("${resilience.http.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${resilience.http.read-timeout-ms:3000}") long readTimeoutMs
    ) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .build()
        );
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(new CorrelationIdClientInterceptor());
    }
}
