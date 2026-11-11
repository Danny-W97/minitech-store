package org.demo.apigateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RedisRateLimiter limiter;
    private final ClientAddressResolver clients;
    private final boolean enabled;
    private final boolean failOpen;

    public RateLimitFilter(
            RedisRateLimiter limiter, ClientAddressResolver clients,
            @Value("${gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${gateway.rate-limit.fail-open:true}") boolean failOpen
    ) {
        this.limiter = limiter;
        this.clients = clients;
        this.enabled = enabled;
        this.failOpen = failOpen;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getServletPath().isBlank() ? request.getRequestURI() : request.getServletPath();
        // Limit unknown/encoded paths too: changing a route, ID or API alias cannot buy a fresh bucket.
        return publicInfrastructure(path, "/actuator") || publicInfrastructure(path, "/v3/api-docs")
                || publicInfrastructure(path, "/swagger-ui") || "/swagger-ui.html".equals(path);
    }

    private boolean publicInfrastructure(String path, String prefix) {
        return prefix.equals(path) || path.startsWith(prefix + "/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String client;
        try {
            client = clients.resolve(request);
        } catch (ClientAddressResolver.InvalidForwardedChain exception) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":400,\"message\":\"Invalid forwarded client address\"}");
            return;
        }
        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.check(client);
        } catch (RuntimeException exception) {
            log.warn("Rate limiter unavailable; failOpen={}", failOpen);
            if (!failOpen) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":503,\"error\":\"Service Unavailable\","
                        + "\"message\":\"Rate limiter is unavailable\"}");
                return;
            }
            decision = null;
        }
        if (decision != null) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
            response.setHeader("X-RateLimit-Reset", String.valueOf(decision.retryAfterSeconds()));
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded\"}");
                return;
            }
        }
        // Deliberately outside the Redis catch: a downstream failure must never replay this request.
        filterChain.doFilter(request, response);
    }
}
