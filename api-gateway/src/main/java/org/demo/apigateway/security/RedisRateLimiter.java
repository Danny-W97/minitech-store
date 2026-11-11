package org.demo.apigateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Component
public class RedisRateLimiter {
    private static final DefaultRedisScript<List> COUNT = new DefaultRedisScript<>("""
            local limit = tonumber(ARGV[2])
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if not count or count < 0 or count % 1 ~= 0 then
                count = limit + 1
                redis.call('SET', KEYS[1], count, 'PX', ARGV[1])
            end
            if count < limit + 1 then count = redis.call('INCR', KEYS[1]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """, List.class);
    private final StringRedisTemplate redis;
    private final int limit;
    private final long windowMillis;

    public RedisRateLimiter(StringRedisTemplate redis,
                            @Value("${gateway.rate-limit.requests-per-window:120}") int limit,
                            @Value("${gateway.rate-limit.window-seconds:60}") long windowSeconds) {
        if (limit < 1 || windowSeconds < 1) throw new IllegalArgumentException("Rate limit settings must be positive");
        this.redis = redis;
        this.limit = limit;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1000L);
    }

    public Decision check(String client) {
        List<?> result = redis.execute(COUNT, List.of(key(client)), String.valueOf(windowMillis), String.valueOf(limit));
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Number count)
                || !(result.get(1) instanceof Number ttl) || count.longValue() < 1 || ttl.longValue() < 0) {
            throw new IllegalStateException("Invalid rate limiter response");
        }
        long retryAfter = Math.max(1, ttl.longValue() / 1000 + (ttl.longValue() % 1000 == 0 ? 0 : 1));
        return new Decision(count.longValue() <= limit, limit, Math.max(0, limit - count.longValue()), retryAfter);
    }

    static String key(String client) {
        return "security:rate-limit:v2:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(client.getBytes(StandardCharsets.UTF_8));
    }
    public record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds) {}
}
