package org.demo.inventoryservice.inventory;

import org.demo.inventoryservice.web.CreateInventoryItemRequest;
import org.demo.inventoryservice.web.InventoryItemResponse;
import org.demo.inventoryservice.web.PatchInventoryItemRequest;
import org.demo.inventoryservice.web.ReservationItemResponse;
import org.demo.inventoryservice.web.ReservationResponse;
import org.demo.inventoryservice.web.ReserveInventoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository inventoryItemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final long reservationTtlSeconds;

    public InventoryService(
            InventoryItemRepository inventoryItemRepository,
            InventoryReservationRepository reservationRepository,
            @Value("${inventory.reservation.ttl-seconds:900}") long reservationTtlSeconds
    ) {
        if (reservationTtlSeconds < 1) {
            throw new IllegalArgumentException("inventory.reservation.ttl-seconds must be at least 1");
        }
        this.inventoryItemRepository = inventoryItemRepository;
        this.reservationRepository = reservationRepository;
        this.reservationTtlSeconds = reservationTtlSeconds;
    }

    @Transactional(readOnly = true)
    public InventoryItemResponse getItem(Long productId) {
        return inventoryItemRepository.findByProductId(productId)
                .map(this::toItemResponse)
                .orElseThrow(() -> new InventoryItemNotFoundException(productId));
    }

    @Transactional
    public InventoryItemResponse createItem(CreateInventoryItemRequest request) {
        if (inventoryItemRepository.existsByProductId(request.productId())) {
            throw new DuplicateInventoryItemException(request.productId());
        }
        return toItemResponse(inventoryItemRepository.save(
                new InventoryItem(request.productId(), request.availableQuantity())
        ));
    }

    @Transactional
    public InventoryItemResponse patchItem(Long productId, PatchInventoryItemRequest request) {
        InventoryItem item = inventoryItemRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new InventoryItemNotFoundException(productId));
        item.setAvailableQuantity(request.availableQuantity());
        return toItemResponse(inventoryItemRepository.save(item));
    }

    @Transactional
    public ReservationResponse reserve(ReserveInventoryRequest request) {
        log.info("Reserving inventory for order {}", request.orderId());
        InventoryReservation existing = reservationRepository.findByOrderId(request.orderId()).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == ReservationStatus.RELEASED) {
                throw new InventoryReservationConflictException("Order already has a released reservation");
            }
            return toReservationResponse(existing);
        }

        Set<Long> productIds = new HashSet<>();
        List<InventoryItem> inventoryItems = new ArrayList<>();
        for (var requestedItem : request.items()) {
            if (!productIds.add(requestedItem.productId())) {
                throw new InventoryReservationConflictException("A product may occur only once per reservation");
            }

            InventoryItem item = inventoryItemRepository.findByProductIdForUpdate(requestedItem.productId())
                    .orElseThrow(() -> new InventoryItemNotFoundException(requestedItem.productId()));
            if (item.getAvailableQuantity() < requestedItem.quantity()) {
                throw new InsufficientStockException(
                        requestedItem.productId(),
                        item.getAvailableQuantity(),
                        requestedItem.quantity()
                );
            }
            inventoryItems.add(item);
        }

        InventoryReservation reservation = new InventoryReservation(
                request.orderId(),
                Instant.now().plus(reservationTtlSeconds, ChronoUnit.SECONDS)
        );
        for (int index = 0; index < request.items().size(); index++) {
            var requestedItem = request.items().get(index);
            inventoryItems.get(index).reserve(requestedItem.quantity());
            reservation.addItem(requestedItem.productId(), requestedItem.quantity());
        }

        return toReservationResponse(reservationRepository.save(reservation));
    }

    @Transactional
    public ReservationResponse release(Long reservationId) {
        InventoryReservation reservation = findReservation(reservationId);
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return toReservationResponse(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new InventoryReservationConflictException("Confirmed reservation cannot be released");
        }

        for (InventoryReservationItem reservationItem : reservation.getItems()) {
            InventoryItem inventoryItem = inventoryItemRepository
                    .findByProductIdForUpdate(reservationItem.getProductId())
                    .orElseThrow(() -> new InventoryItemNotFoundException(reservationItem.getProductId()));
            inventoryItem.release(reservationItem.getQuantity());
        }
        reservation.changeStatus(ReservationStatus.RELEASED);
        return toReservationResponse(reservationRepository.save(reservation));
    }

    @Transactional
    public ReservationResponse confirm(Long reservationId) {
        log.info("Confirming inventory reservation {}", reservationId);
        InventoryReservation reservation = findReservation(reservationId);
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return toReservationResponse(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new InventoryReservationConflictException("Released reservation cannot be confirmed");
        }

        for (InventoryReservationItem reservationItem : reservation.getItems()) {
            InventoryItem inventoryItem = inventoryItemRepository
                    .findByProductIdForUpdate(reservationItem.getProductId())
                    .orElseThrow(() -> new InventoryItemNotFoundException(reservationItem.getProductId()));
            inventoryItem.confirm(reservationItem.getQuantity());
        }
        reservation.changeStatus(ReservationStatus.CONFIRMED);
        return toReservationResponse(reservationRepository.save(reservation));
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(Long reservationId) {
        return toReservationResponse(findReservation(reservationId));
    }

    @Transactional
    @Scheduled(fixedDelayString = "${inventory.reservation.cleanup-fixed-delay-ms:60000}")
    public void releaseExpiredReservations() {
        List<InventoryReservation> expired = reservationRepository
                .findAllByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, Instant.now());
        for (InventoryReservation reservation : expired) {
            release(reservation.getId());
        }
    }

    private InventoryReservation findReservation(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new InventoryReservationNotFoundException(reservationId));
    }

    private InventoryItemResponse toItemResponse(InventoryItem item) {
        return new InventoryItemResponse(
                item.getProductId(),
                item.getAvailableQuantity(),
                item.getReservedQuantity(),
                item.getSoldQuantity(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    private ReservationResponse toReservationResponse(InventoryReservation reservation) {
        List<ReservationItemResponse> items = reservation.getItems().stream()
                .map(item -> new ReservationItemResponse(item.getProductId(), item.getQuantity()))
                .toList();
        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                items
        );
    }
}
