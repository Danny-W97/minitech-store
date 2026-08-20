package org.demo.apigateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "security:rate-limit:";
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final int requestsPerWindow;
    private final long windowSeconds;
    private final boolean failOpen;

    public RateLimitFilter(
            StringRedisTemplate redisTemplate,
            @Value("${gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${gateway.rate-limit.requests-per-window:120}") int requestsPerWindow,
            @Value("${gateway.rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${gateway.rate-limit.fail-open:true}") boolean failOpen
    ) {
        if (requestsPerWindow < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("Rate limit settings must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.requestsPerWindow = requestsPerWindow;
        this.windowSeconds = windowSeconds;
        this.failOpen = failOpen;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
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

        String key = KEY_PREFIX + encoded(clientAddress(request)) + ":"
                + encoded(request.getMethod() + ":" + request.getRequestURI());
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
            }
            long retryAfter = redisTemplate.getExpire(key);
            if (retryAfter < 1) {
                retryAfter = windowSeconds;
            }
            long remaining = Math.max(0, requestsPerWindow - (count == null ? 0 : count));
            response.setHeader("X-RateLimit-Limit", String.valueOf(requestsPerWindow));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            response.setHeader("X-RateLimit-Reset", String.valueOf(retryAfter));
            if (count != null && count > requestsPerWindow) {
                log.warn("Rate limit exceeded: method={} path={} client={} correlationId={}",
                        request.getMethod(), request.getRequestURI(), clientAddress(request),
                        request.getHeader("X-Correlation-Id"));
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                        + "\"message\":\"Rate limit exceeded\"}");
                return;
            }
            filterChain.doFilter(request, response);
        } catch (RuntimeException exception) {
            if (!failOpen) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":503,\"error\":\"Service Unavailable\","
                        + "\"message\":\"Rate limiter is unavailable\"}");
                return;
            }
            filterChain.doFilter(request, response);
        }
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
