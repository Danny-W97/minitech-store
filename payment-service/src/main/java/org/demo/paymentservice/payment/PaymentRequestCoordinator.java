package org.demo.paymentservice.payment;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Serializes concurrent payment requests for the same order inside one service
 * instance. The controller holds the lock while the transactional service call
 * completes, so a following retry can observe the committed payment row.
 */
@Component
public class PaymentRequestCoordinator {

    private static final int STRIPE_COUNT = 256;

    private final ReentrantLock[] locks = IntStream.range(0, STRIPE_COUNT)
            .mapToObj(ignored -> new ReentrantLock())
            .toArray(ReentrantLock[]::new);

    public <T> T execute(Long orderId, Supplier<T> operation) {
        ReentrantLock lock = locks[Math.floorMod(Long.hashCode(orderId), locks.length)];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
