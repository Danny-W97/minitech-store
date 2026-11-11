package org.demo.inventoryservice.inventory;

import org.demo.inventoryservice.web.CreateInventoryItemRequest;
import org.demo.inventoryservice.web.InventoryItemResponse;
import org.demo.inventoryservice.web.ReservationItemResponse;
import org.demo.inventoryservice.web.ReservationResponse;
import org.demo.inventoryservice.web.ReserveInventoryRequest;
import org.demo.inventoryservice.web.ReserveInventoryItemRequest;
import org.springframework.data.domain.PageRequest;
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
import java.util.Comparator;

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

    @Transactional(readOnly = true)
    public List<InventoryItemResponse> getItems(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty() || productIds.size() > 100
                || productIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Provide between 1 and 100 positive product IDs");
        }
        var found = new java.util.HashMap<Long, InventoryItem>();
        inventoryItemRepository.findByProductIdIn(productIds.stream().distinct().toList())
                .forEach(item -> found.put(item.getProductId(), item));
        return productIds.stream().distinct().filter(found::containsKey)
                .map(id -> toItemResponse(found.get(id))).toList();
    }

    @Transactional
    public InventoryItemResponse createItem(CreateInventoryItemRequest request) {
        if (inventoryItemRepository.existsByProductId(request.productId())) {
            throw new DuplicateInventoryItemException(request.productId());
        }
        return toItemResponse(inventoryItemRepository.saveAndFlush(
                new InventoryItem(request.productId(), request.availableQuantity())
        ));
    }

    @Transactional
    public ReservationResponse reserve(ReserveInventoryRequest request) {
        log.info("Reserving inventory for order {}", request.orderId());
        List<ReserveInventoryItemRequest> requestedItems = request.items().stream()
                .sorted(Comparator.comparing(ReserveInventoryItemRequest::productId)).toList();
        Set<Long> productIds = new HashSet<>();
        for (var requestedItem : requestedItems) {
            if (!productIds.add(requestedItem.productId())) {
                throw new InventoryReservationConflictException("A product may occur only once per reservation");
            }
        }
        InventoryReservation existing = reservationRepository.findByOrderIdForUpdate(request.orderId()).orElse(null);
        if (existing != null) {
            return reuseReservation(existing, requestedItems);
        }

        List<InventoryItem> inventoryItems = new ArrayList<>();
        for (var requestedItem : requestedItems) {
            InventoryItem item = inventoryItemRepository.findByProductIdForUpdate(requestedItem.productId())
                    .orElseThrow(() -> new InventoryItemNotFoundException(requestedItem.productId()));
            inventoryItems.add(item);
        }
        // A concurrent creator can have committed while we waited for product locks.
        // This read must not take a reservation lock after the product locks.
        existing = reservationRepository.findByOrderId(request.orderId()).orElse(null);
        if (existing != null) {
            return reuseReservation(existing, requestedItems);
        }
        for (int index = 0; index < requestedItems.size(); index++) {
            var requestedItem = requestedItems.get(index);
            InventoryItem item = inventoryItems.get(index);
            if (item.getAvailableQuantity() < requestedItem.quantity()) {
                throw new InsufficientStockException(
                        requestedItem.productId(),
                        item.getAvailableQuantity(),
                        requestedItem.quantity()
                );
            }
        }

        InventoryReservation reservation = new InventoryReservation(
                request.orderId(),
                Instant.now().plus(reservationTtlSeconds, ChronoUnit.SECONDS)
        );
        for (int index = 0; index < requestedItems.size(); index++) {
            var requestedItem = requestedItems.get(index);
            inventoryItems.get(index).reserve(requestedItem.quantity());
            reservation.addItem(requestedItem.productId(), requestedItem.quantity());
        }

        return toReservationResponse(reservationRepository.save(reservation));
    }

    @Transactional
    public ReservationResponse release(Long reservationId) {
        InventoryReservation reservation = findReservationForUpdate(reservationId);
        return releaseReservation(reservation);
    }

    private ReservationResponse releaseReservation(InventoryReservation reservation) {
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return toReservationResponse(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new InventoryReservationConflictException("Confirmed reservation cannot be released");
        }

        for (InventoryReservationItem reservationItem : sortedItems(reservation)) {
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
        InventoryReservation reservation = findReservationForUpdate(reservationId);
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return toReservationResponse(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new InventoryReservationConflictException("Released reservation cannot be confirmed");
        }

        for (InventoryReservationItem reservationItem : sortedItems(reservation)) {
            InventoryItem inventoryItem = inventoryItemRepository
                    .findByProductIdForUpdate(reservationItem.getProductId())
                    .orElseThrow(() -> new InventoryItemNotFoundException(reservationItem.getProductId()));
            inventoryItem.confirm(reservationItem.getQuantity());
        }
        reservation.changeStatus(ReservationStatus.CONFIRMED);
        return toReservationResponse(reservationRepository.save(reservation));
    }

    @Transactional
    public ReservationResponse extendForCheckout(Long reservationId,
            org.demo.inventoryservice.web.ExtendCheckoutReservationRequest request) {
        Instant now=Instant.now();
        long seconds=java.time.Duration.between(now,request.expiresAt()).toSeconds();
        if(seconds<30*60 || seconds>45*60)
            throw new InventoryReservationConflictException("Checkout reservation extension must be between 30 and 45 minutes");
        InventoryReservation reservation=findReservationForUpdate(reservationId);
        if(!reservation.getOrderId().equals(request.orderId()))
            throw new InventoryReservationConflictException("Reservation does not belong to this order");
        reservation.extendForCheckout(request.expiresAt(),now);
        return toReservationResponse(reservationRepository.saveAndFlush(reservation));
    }

    @Transactional
    public ReservationResponse compensateStripe(Long reservationId,Long orderId) {
        var reservation=findReservationForUpdate(reservationId);
        if(!reservation.getOrderId().equals(orderId)) throw new InventoryReservationConflictException("Reservation order mismatch");
        if(reservation.getStatus()!=ReservationStatus.CONFIRMED) return releaseReservation(reservation);
        for(var line:sortedItems(reservation)) {
            var item=inventoryItemRepository.findByProductIdForUpdate(line.getProductId()).orElseThrow();
            item.reverseUnfulfilledSale(line.getQuantity());
        }
        reservation.changeStatus(ReservationStatus.RELEASED);
        return toReservationResponse(reservationRepository.saveAndFlush(reservation));
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(Long reservationId) {
        return toReservationResponse(findReservation(reservationId));
    }
    @Transactional(readOnly=true)
    public ReservationResponse getReservationForOrder(Long orderId) {
        return reservationRepository.findByOrderId(orderId).map(this::toReservationResponse)
                .orElseThrow(() -> new InventoryReservationNotFoundException(orderId));
    }

    @Transactional
    @Scheduled(fixedDelayString = "${inventory.reservation.cleanup-fixed-delay-ms:60000}")
    public void releaseExpiredReservations() {
        Instant now = Instant.now();
        List<Long> expiredIds = reservationRepository
                .findExpiredIds(ReservationStatus.RESERVED, now, PageRequest.of(0, 100));
        for (Long id : expiredIds) {
            InventoryReservation reservation = findReservationForUpdate(id);
            if (reservation.getStatus() == ReservationStatus.RESERVED
                    && reservation.getExpiresAt().isBefore(now)) {
                releaseReservation(reservation);
            }
        }
    }

    private ReservationResponse reuseReservation(InventoryReservation reservation,
                                                List<ReserveInventoryItemRequest> requestedItems) {
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new InventoryReservationConflictException("Order already has a released reservation");
        }
        List<InventoryReservationItem> items = sortedItems(reservation);
        if (items.size() != requestedItems.size()) {
            throw new InventoryReservationConflictException("Order already has a different reservation");
        }
        for (int index = 0; index < items.size(); index++) {
            if (!items.get(index).getProductId().equals(requestedItems.get(index).productId())
                    || !items.get(index).getQuantity().equals(requestedItems.get(index).quantity())) {
                throw new InventoryReservationConflictException("Order already has a different reservation");
            }
        }
        return toReservationResponse(reservation);
    }

    private List<InventoryReservationItem> sortedItems(InventoryReservation reservation) {
        return reservation.getItems().stream()
                .sorted(Comparator.comparing(InventoryReservationItem::getProductId)).toList();
    }

    private InventoryReservation findReservationForUpdate(Long id) {
        return reservationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new InventoryReservationNotFoundException(id));
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
                item.getUpdatedAt(),item.getVersion()
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
