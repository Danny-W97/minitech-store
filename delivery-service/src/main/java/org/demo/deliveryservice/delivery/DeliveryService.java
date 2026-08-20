package org.demo.deliveryservice.delivery;

import org.demo.deliveryservice.web.DeliveryMethodResponse;
import org.demo.deliveryservice.web.DeliveryQuoteRequest;
import org.demo.deliveryservice.web.DeliveryQuoteResponse;
import org.demo.deliveryservice.web.ShipmentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DeliveryService {

    private final DeliveryMethodRepository deliveryMethodRepository;
    private final ShipmentRepository shipmentRepository;

    private static final Set<String> SHIPMENT_STATUSES = Set.of(
            Shipment.LABEL_CREATED, Shipment.READY_FOR_PICKUP, Shipment.IN_TRANSIT,
            Shipment.OUT_FOR_DELIVERY, Shipment.DELIVERED, Shipment.PICKED_UP,
            Shipment.CANCELLED
    );

    public DeliveryService(DeliveryMethodRepository deliveryMethodRepository,
                           ShipmentRepository shipmentRepository) {
        this.deliveryMethodRepository = deliveryMethodRepository;
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional(readOnly = true)
    public List<DeliveryMethodResponse> getMethods() {
        return deliveryMethodRepository.findAllByActiveTrueOrderByBasePriceAsc()
                .stream()
                .map(this::toMethodResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeliveryQuoteResponse quote(DeliveryQuoteRequest request) {
        String code = request.methodCode().trim().toUpperCase();
        DeliveryMethod method = deliveryMethodRepository.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> new DeliveryMethodNotFoundException(code));

        BigDecimal subtotal = money(request.subtotal());
        BigDecimal cost = money(method.getBasePrice());
        if (method.getFreeDeliveryFrom() != null && subtotal.compareTo(method.getFreeDeliveryFrom()) >= 0) {
            cost = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        List<LocalDate> availableDates = availableDates(method);
        if (request.requestedDate() != null && !availableDates.contains(request.requestedDate())) {
            throw new DeliveryDateUnavailableException(method.getCode(), request.requestedDate());
        }
        LocalDate selectedDate = request.requestedDate() == null
                ? availableDates.get(0)
                : request.requestedDate();
        String fulfillmentType = "STORE_PICKUP".equals(method.getCode())
                ? "STORE_PICKUP" : "HOME_DELIVERY";
        return new DeliveryQuoteResponse(
                method.getId(),
                method.getCode(),
                method.getName(),
                method.getDescription(),
                cost,
                subtotal,
                subtotal.add(cost).setScale(2, RoundingMode.HALF_UP),
                cost.signum() == 0,
                method.getEtaMinDays(),
                method.getEtaMaxDays(),
                fulfillmentType,
                availableDates,
                selectedDate
        );
    }

    private List<LocalDate> availableDates(DeliveryMethod method) {
        ZoneId zone = ZoneId.of("Europe/Warsaw");
        LocalDate first = LocalDate.now(zone).plusDays(method.getEtaMinDays());
        LocalDate last = LocalDate.now(zone).plusDays(method.getEtaMaxDays());
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                dates.add(date);
            }
        }
        if (dates.isEmpty()) {
            dates.add(first);
        }
        return dates;
    }

    @Transactional
    public ShipmentResponse ensureTracking(Long orderId, String methodCode) {
        String resolvedMethod = methodCode == null || methodCode.isBlank()
                ? "STANDARD_COURIER" : methodCode.trim().toUpperCase(Locale.ROOT);
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseGet(() -> shipmentRepository.save(new Shipment(orderId, resolvedMethod)));
        return toShipmentResponse(shipment);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse tracking(Long orderId) {
        return shipmentRepository.findByOrderId(orderId)
                .map(this::toShipmentResponse)
                .orElseThrow(() -> new ShipmentNotFoundException(orderId));
    }

    @Transactional
    public ShipmentResponse updateTrackingStatus(Long orderId, String requestedStatus) {
        String status = requestedStatus.trim().toUpperCase(Locale.ROOT);
        if (!SHIPMENT_STATUSES.contains(status)) {
            throw new InvalidShipmentStatusException(status);
        }
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ShipmentNotFoundException(orderId));
        shipment.updateStatus(status);
        return toShipmentResponse(shipmentRepository.save(shipment));
    }

    private ShipmentResponse toShipmentResponse(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getOrderId(), shipment.getTrackingNumber(), shipment.getCarrier(),
                shipment.getMethodCode(), shipment.getStatus(), shipment.getCreatedAt(), shipment.getUpdatedAt()
        );
    }

    private DeliveryMethodResponse toMethodResponse(DeliveryMethod method) {
        return new DeliveryMethodResponse(
                method.getId(), method.getCode(), method.getName(), method.getDescription(),
                money(method.getBasePrice()), method.getFreeDeliveryFrom() == null ? null : money(method.getFreeDeliveryFrom()),
                method.getEtaMinDays(), method.getEtaMaxDays(), method.isActive()
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
