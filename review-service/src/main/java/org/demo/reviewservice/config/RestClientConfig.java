package org.demo.reviewservice.config;

import org.demo.platformobservability.CorrelationIdClientInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;


@Configuration
public class RestClientConfig {
    @Bean
    RestClient.Builder restClientBuilder(
            @Value("${resilience.http.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${resilience.http.read-timeout-ms:3000}") long readTimeoutMs
    ) {
        var requestFactory = new org.demo.platformsecurity.BoundedHttpRequestFactory(connectTimeoutMs,readTimeoutMs);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(new CorrelationIdClientInterceptor());
    }
}
