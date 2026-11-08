package org.demo.inventoryservice.inventory;

import org.demo.inventoryservice.web.CreateInventoryItemRequest;
import org.demo.inventoryservice.web.ReserveInventoryItemRequest;
import org.demo.inventoryservice.web.ReserveInventoryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "inventory.reservation.cleanup-fixed-delay-ms=3600000")
class InventoryConcurrencyTest {
    @Autowired private InventoryService service;
    @Autowired private InventoryItemRepository items;
    @MockitoSpyBean private InventoryReservationRepository reservations;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        items.deleteAll();
        service.createItem(new CreateInventoryItemRequest(101L, 10));
    }

    private Long reserve(Long orderId, int quantity) {
        return service.reserve(new ReserveInventoryRequest(orderId,
                List.of(new ReserveInventoryItemRequest(101L, quantity)))).reservationId();
    }

    private void concurrently(Runnable operation) throws Exception {
        int workers = 8;
        try (var executor = Executors.newFixedThreadPool(workers)) {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent start timed out");
                    operation.run();
                    return null;
                }));
            }
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentConfirmationsDoNotConsumeAnotherOrdersReservation() throws Exception {
        Long first = reserve(1L, 2);
        reserve(2L, 3);

        concurrently(() -> service.confirm(first));

        var item = service.getItem(101L);
        assertThat(item.availableQuantity()).isEqualTo(5);
        assertThat(item.reservedQuantity()).isEqualTo(3);
        assertThat(item.soldQuantity()).isEqualTo(2);
        assertThat(service.getReservation(first).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void concurrentReleasesDoNotReturnAnotherOrdersStock() throws Exception {
        Long first = reserve(1L, 2);
        reserve(2L, 3);

        concurrently(() -> service.release(first));

        var item = service.getItem(101L);
        assertThat(item.availableQuantity()).isEqualTo(7);
        assertThat(item.reservedQuantity()).isEqualTo(3);
        assertThat(item.soldQuantity()).isZero();
    }

    @Test
    void concurrentFirstRequestsReserveStockOnceForTheSameOrder() throws Exception {
        concurrently(() -> reserve(1L, 2));

        assertThat(reservations.count()).isEqualTo(1);
        assertThat(service.getItem(101L).availableQuantity()).isEqualTo(8);
        assertThat(service.getItem(101L).reservedQuantity()).isEqualTo(2);
    }

    @Test
    void changedReservationPayloadIsRejectedWithoutChangingStock() {
        reserve(1L, 2);
        assertThatThrownBy(() -> reserve(1L, 3)).isInstanceOf(InventoryReservationConflictException.class);
        assertThat(service.getItem(101L).reservedQuantity()).isEqualTo(2);
    }

    @Test
    void reversedProductOrderingDoesNotDeadlockReservations() throws Exception {
        service.createItem(new CreateInventoryItemRequest(102L, 10));
        var sequence = new java.util.concurrent.atomic.AtomicLong(1);
        concurrently(() -> {
            long orderId = sequence.getAndIncrement();
            List<ReserveInventoryItemRequest> requested = orderId % 2 == 0
                    ? List.of(new ReserveInventoryItemRequest(101L, 1), new ReserveInventoryItemRequest(102L, 1))
                    : List.of(new ReserveInventoryItemRequest(102L, 1), new ReserveInventoryItemRequest(101L, 1));
            service.reserve(new ReserveInventoryRequest(orderId, requested));
        });
        assertThat(reservations.count()).isEqualTo(8);
        assertThat(service.getItem(101L).reservedQuantity()).isEqualTo(8);
        assertThat(service.getItem(102L).reservedQuantity()).isEqualTo(8);
    }

    @Test
    void expiryCleanupRechecksStatusAfterAcquiringLock() {
        Long first = reserve(1L, 2);
        reserve(2L, 3);
        InventoryReservation expired = reservations.findById(first).orElseThrow();
        ReflectionTestUtils.setField(expired, "expiresAt", Instant.now().minusSeconds(60));
        reservations.save(expired);
        doAnswer(invocation -> {
            // Simulate confirmation after selection of expired IDs but before cleanup locks the row.
            service.confirm(first);
            return List.of(first);
        }).when(reservations).findExpiredIds(eq(ReservationStatus.RESERVED), any(), any());

        service.releaseExpiredReservations();

        assertThat(service.getReservation(first).status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(service.getItem(101L).reservedQuantity()).isEqualTo(3);
        assertThat(service.getItem(101L).soldQuantity()).isEqualTo(2);
    }
}
