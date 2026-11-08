package org.demo.apigateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisRateLimiterTest {
    @Test void validatesSettingsBeforeUsingRedis() {
        assertThatThrownBy(() -> new RedisRateLimiter(null, 0, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRateLimiter(null, 10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRateLimiter(null, 10, Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
    }
    @Test @SuppressWarnings("unchecked") void roundsRetryAfterUpAndNeverReturnsNegativeRemaining() {
        var redis = mock(StringRedisTemplate.class);
        doReturn(List.of(5L, 1001L), List.of(11L, 1L)).when(redis).execute(any(RedisScript.class), anyList(), any(), any());
        var limiter = new RedisRateLimiter(redis, 10, 60);
        assertThat(limiter.check("198.51.100.7")).isEqualTo(new RedisRateLimiter.Decision(true, 10, 5, 2));
        assertThat(limiter.check("198.51.100.7")).isEqualTo(new RedisRateLimiter.Decision(false, 10, 0, 1));
        verify(redis, times(2)).execute(any(RedisScript.class), eq(List.of(RedisRateLimiter.key("198.51.100.7"))), eq("60000"), eq("10"));
    }
    @Test @SuppressWarnings("unchecked") void missingOrInvalidRedisResultCannotBeTreatedAsAnAllowedRequest() {
        var redis = mock(StringRedisTemplate.class);
        doReturn(null, List.of(), Arrays.asList(null, 10L), List.of(1L, -1L))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());
        var limiter = new RedisRateLimiter(redis, 10, 60);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> limiter.check("198.51.100.7")).isInstanceOf(IllegalStateException.class);
        }
    }
}
