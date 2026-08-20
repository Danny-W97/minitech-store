package org.demo.customerservice.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class LoginProtectionService {

    private static final String FAILURE_KEY_PREFIX = "security:login:failures:";
    private static final String LOCK_KEY_PREFIX = "security:login:lock:";

    private final StringRedisTemplate redisTemplate;
    private final int maxFailedAttempts;
    private final long failureWindowSeconds;
    private final long lockSeconds;

    public LoginProtectionService(
            StringRedisTemplate redisTemplate,
            @Value("${customer.login-protection.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${customer.login-protection.failure-window-seconds:900}") long failureWindowSeconds,
            @Value("${customer.login-protection.lock-seconds:900}") long lockSeconds
    ) {
        if (maxFailedAttempts < 1 || failureWindowSeconds < 1 || lockSeconds < 1) {
            throw new IllegalArgumentException("Login protection settings must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.maxFailedAttempts = maxFailedAttempts;
        this.failureWindowSeconds = failureWindowSeconds;
        this.lockSeconds = lockSeconds;
    }

    public void assertNotLocked(String normalizedEmail) {
        Long ttl = redisTemplate.getExpire(lockKey(normalizedEmail));
        if (ttl != null && ttl > 0) {
            throw new AccountLockedException(ttl);
        }
    }

    public boolean recordFailure(String normalizedEmail) {
        String key = failureKey(normalizedEmail);
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(failureWindowSeconds));
        }
        if (attempts != null && attempts >= maxFailedAttempts) {
            redisTemplate.opsForValue().set(
                    lockKey(normalizedEmail),
                    "1",
                    Duration.ofSeconds(lockSeconds)
            );
            redisTemplate.delete(key);
            return true;
        }
        return false;
    }

    public void clearFailures(String normalizedEmail) {
        redisTemplate.delete(failureKey(normalizedEmail));
        redisTemplate.delete(lockKey(normalizedEmail));
    }

    public long lockSeconds() {
        return lockSeconds;
    }

    private String failureKey(String email) {
        return FAILURE_KEY_PREFIX + email;
    }

    private String lockKey(String email) {
        return LOCK_KEY_PREFIX + email;
    }
}
