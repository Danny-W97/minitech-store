package org.demo.orderservice.order;

import org.demo.orderservice.inventory.InventoryClient;
import org.demo.orderservice.inventory.InventoryConflictException;
import org.demo.orderservice.inventory.InventoryReservationItemRequest;
import org.demo.orderservice.inventory.InventoryReservationResponse;
import org.demo.orderservice.web.OrderSagaResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

@Service
public class OrderSagaService {

    private final OrderSagaRepository sagaRepository;
    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final long initialRetryBackoffMs;
    private final long maxRetryBackoffMs;

    public OrderSagaService(
            OrderSagaRepository sagaRepository,
            OrderRepository orderRepository,
            InventoryClient inventoryClient,
            @Value("${order.saga.retry.initial-backoff-ms:1000}") long initialRetryBackoffMs,
            @Value("${order.saga.retry.max-backoff-ms:60000}") long maxRetryBackoffMs
    ) {
        if (initialRetryBackoffMs < 1 || maxRetryBackoffMs < initialRetryBackoffMs) {
            throw new IllegalArgumentException("Order saga retry backoff configuration is invalid");
        }
        this.sagaRepository = sagaRepository;
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.initialRetryBackoffMs = initialRetryBackoffMs;
        this.maxRetryBackoffMs = maxRetryBackoffMs;
    }

    @Transactional
    public void start(Long orderId) {
        sagaRepository.findByOrderId(orderId)
                .orElseGet(() -> sagaRepository.save(new OrderSaga(orderId)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = RuntimeException.class)
    public void resume(Long orderId) {
        sagaRepository.findByOrderIdForUpdate(orderId).ifPresent(this::process);
    }

    @Scheduled(fixedDelayString = "${order.saga.recovery-fixed-delay-ms:5000}")
    @Transactional
    public void resumeDueSagas() {
        Instant now = Instant.now();
        var states = EnumSet.of(
                OrderSagaState.INVENTORY_RESERVATION_PENDING,
                OrderSagaState.COMPENSATION_PENDING
        );
        sagaRepository
                .findTop100ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(states, now)
                .forEach(this::processSafely);
    }

    @Transactional(readOnly = true)
    public OrderSagaResponse getStatus(Long orderId) {
        return sagaRepository.findByOrderId(orderId)
                .map(this::toResponse)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional
    public void markPaymentConfirmed(Long orderId) {
        sagaRepository.findByOrderId(orderId).ifPresent(saga -> {
            if (saga.getState() == OrderSagaState.WAITING_FOR_PAYMENT) {
                saga.completed();
                sagaRepository.save(saga);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = RuntimeException.class)
    public void compensate(Long orderId) {
        Order order = findOrder(orderId);
        OrderSaga saga = sagaRepository.findByOrderId(orderId).orElse(null);
        if (saga == null || saga.getState() == OrderSagaState.COMPLETED) {
            order.changeStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            return;
        }

        try {
            if (order.getInventoryReservationId() != null) {
                inventoryClient.release(order.getInventoryReservationId());
            }
            order.changeStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            saga.failed("Order cancelled and inventory compensation completed");
            sagaRepository.save(saga);
        } catch (RuntimeException exception) {
            saga.compensationPending(Instant.now(), errorMessage(exception));
            scheduleRetry(saga, exception);
            sagaRepository.save(saga);
            throw exception;
        }
    }

    private void processSafely(OrderSaga saga) {
        try {
            process(saga);
        } catch (RuntimeException exception) {
            // The failed attempt is already persisted. The next scheduler run
            // will use next_attempt_at and continue the saga after a restart.
        }
    }

    private void process(OrderSaga saga) {
        if (saga.getState() == OrderSagaState.INVENTORY_RESERVATION_PENDING) {
            reserveInventory(saga);
        } else if (saga.getState() == OrderSagaState.COMPENSATION_PENDING) {
            compensate(saga);
        }
    }

    private void reserveInventory(OrderSaga saga) {
        Order order = findOrder(saga.getOrderId());
        try {
            List<InventoryReservationItemRequest> items = order.getItems().stream()
                    .map(item -> new InventoryReservationItemRequest(item.getProductId(), item.getQuantity()))
                    .toList();
            InventoryReservationResponse reservation = inventoryClient.reserve(order.getId(), items);
            order.assignInventoryReservation(reservation.reservationId());
            order.changeStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            saga.waitingForPayment();
            sagaRepository.save(saga);
        } catch (InventoryConflictException exception) {
            order.changeStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            saga.failed(exception.getMessage());
            sagaRepository.save(saga);
            throw exception;
        } catch (RuntimeException exception) {
            scheduleRetry(saga, exception);
            sagaRepository.save(saga);
            throw exception;
        }
    }

    private void compensate(OrderSaga saga) {
        Order order = findOrder(saga.getOrderId());
        try {
            if (order.getInventoryReservationId() != null) {
                inventoryClient.release(order.getInventoryReservationId());
            }
            order.changeStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            saga.failed("Order cancelled and inventory compensation completed");
            sagaRepository.save(saga);
        } catch (RuntimeException exception) {
            saga.compensationPending(Instant.now(), errorMessage(exception));
            scheduleRetry(saga, exception);
            sagaRepository.save(saga);
        }
    }

    private void scheduleRetry(OrderSaga saga, RuntimeException exception) {
        long exponent = Math.min(Math.max(0, saga.getAttemptCount() - 1), 10);
        long multiplier = 1L << exponent;
        long delay = Math.min(maxRetryBackoffMs, initialRetryBackoffMs * multiplier);
        saga.recordAttempt(
                Instant.now().plus(delay, ChronoUnit.MILLIS),
                errorMessage(exception)
        );
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private OrderSagaResponse toResponse(OrderSaga saga) {
        return new OrderSagaResponse(
                saga.getOrderId(),
                saga.getState(),
                saga.getAttemptCount(),
                saga.getNextAttemptAt(),
                saga.getLastError(),
                saga.getCreatedAt(),
                saga.getUpdatedAt()
        );
    }
}
