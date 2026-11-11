package org.demo.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;


@Configuration
public class RestClientConfig {
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<org.demo.platformsecurity.RequestBudgetFilter> requestBudgetFilter(
            @Value("${resilience.http.request-budget-ms:15000}") long milliseconds) {
        var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new org.demo.platformsecurity.RequestBudgetFilter(milliseconds));
        bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+10); return bean;
    }

    @Bean
    RestClient.Builder restClientBuilder(
            @Value("${resilience.http.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${resilience.http.read-timeout-ms:15000}") long readTimeoutMs
    ) {
        var requestFactory = new org.demo.platformsecurity.BoundedHttpRequestFactory(connectTimeoutMs,readTimeoutMs);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
