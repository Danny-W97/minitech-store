package org.demo.notificationservice.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class NotificationRetryPolicy {

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public NotificationRetryPolicy(
            @Value("${notification.retry.max-attempts:3}") int maxAttempts,
            @Value("${notification.retry.initial-backoff-ms:5000}") long initialBackoffMs,
            @Value("${notification.retry.max-backoff-ms:60000}") long maxBackoffMs
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("notification.retry.max-attempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
    }

    public Instant nextAttemptAt(int currentAttemptCount) {
        if (currentAttemptCount >= maxAttempts) {
            return null;
        }

        int exponent = Math.max(0, Math.min(currentAttemptCount - 1, 10));
        long multiplier = 1L << exponent;
        long backoff = Math.min(maxBackoffMs, initialBackoffMs * multiplier);
        return Instant.now().plusMillis(backoff);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
