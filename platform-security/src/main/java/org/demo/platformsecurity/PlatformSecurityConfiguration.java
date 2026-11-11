package org.demo.platformsecurity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@AutoConfiguration
public class PlatformSecurityConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder boundedRestClientBuilder() { return RestClient.builder(); }
    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor serviceClientAuthentication(
            @Value("${PLATFORM_SERVICE_TOKEN:}") String token,
            @Value("${resilience.http.connect-timeout-ms:1000}") long connect,
            @Value("${resilience.http.read-timeout-ms:3000}") long read) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof RestClient.Builder builder) builder.requestFactory(new BoundedHttpRequestFactory(connect,read))
                        .requestInterceptor(new ServiceAuthenticationInterceptor(token));
                return bean;
            }
        };
    }
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> requestBudgetFilter(
            @Value("${resilience.http.request-budget-ms:15000}") long milliseconds) {
        var registration=new FilterRegistrationBean<OncePerRequestFilter>(new RequestBudgetFilter(milliseconds));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+10); return registration;
    }
    @Bean
    ServiceAuthenticationInterceptor serviceAuthenticationInterceptor(@Value("${PLATFORM_SERVICE_TOKEN:}") String token) {
        return new ServiceAuthenticationInterceptor(token);
    }
    @Bean
    OrderAccess orderAccess(@Value("${PLATFORM_ORDER_URL:${ORDER_SERVICE_URL:http://localhost:18082}}") String url,
                            ServiceAuthenticationInterceptor authentication) {
        return new OrderAccess(client().baseUrl(url).requestInterceptor(authentication).build());
    }
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> platformAuthenticationFilter(
            @Value("${spring.application.name:unknown-service}") String service,
            @Value("${PLATFORM_AUTH_URL:${CUSTOMER_SERVICE_URL:http://localhost:18087}}") String authUrl,
            @Value("${PLATFORM_SERVICE_TOKEN:}") String ownToken) {
        var client = client().baseUrl(authUrl).build();
        var filter = new OncePerRequestFilter() {
            @Override protected boolean shouldNotFilter(HttpServletRequest request) {
                return "customer-service".equals(service);
            }
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Actor actor = Actor.ANONYMOUS;
                String authorization = request.getHeader("Authorization");
                if (authorization != null) {
                    if (!authorization.startsWith("Bearer ") || authorization.length() > 8192) { deny(response, 401); return; }
                    if (ownToken.isBlank()) { deny(response, 503); return; }
                    try {
                        actor = client.post().uri("/internal/auth/introspect").header("X-Service-Token", ownToken)
                                .body(Map.of("token", authorization.substring(7))).retrieve().body(Actor.class);
                    } catch (RestClientException exception) { deny(response, 503); return; }
                    if (actor == null || !actor.authenticated()) { deny(response, 401); return; }
                }
                request.setAttribute(Access.ACTOR, actor);
                String path = request.getRequestURI();
                if (!RoutePolicy.allowed(request.getMethod(), path, actor)) { deny(response, actor.authenticated() ? 403 : 401); return; }
                // Every customer-id route uses the verified subject, including direct-port calls.
                var matcher = java.util.regex.Pattern.compile("/(?:orders|invoices|returns|reviews|recommendations|wishlists)/customer/(\\d+)(?:/.*)?").matcher(path);
                if (matcher.matches() && !actor.operator() && !actor.internal()
                        && !Long.valueOf(matcher.group(1)).equals(actor.customerId())) { deny(response, 403); return; }
                response.setHeader("Cache-Control", "no-store");
                chain.doFilter(request, response);
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
    public static RestClient.Builder client() {
        return RestClient.builder().requestFactory(new BoundedHttpRequestFactory(1000,3000));
    }
    private static void deny(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + (status == 503 ? "Authentication service unavailable" : "Access denied") + "\"}");
    }
}
