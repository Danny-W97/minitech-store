package org.demo.deliveryservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.demo.deliveryservice.delivery.DeliveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/deliveries")
@Tag(name = "Delivery", description = "Delivery methods and shipping quotes")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/methods")
    @Operation(summary = "List active delivery methods")
    public List<DeliveryMethodResponse> methods() {
        return deliveryService.getMethods();
    }

    @PostMapping("/quote")
    @Operation(summary = "Calculate a delivery quote for the current basket")
    public DeliveryQuoteResponse quote(@Valid @RequestBody DeliveryQuoteRequest request) {
        return deliveryService.quote(request);
    }

    @PostMapping("/orders/{orderId}/tracking")
    @Operation(summary = "Create or return shipment tracking for a paid order")
    public ShipmentResponse ensureTracking(@PathVariable Long orderId,
                                            @RequestParam(required = false) String methodCode) {
        return deliveryService.ensureTracking(orderId, methodCode);
    }

    @GetMapping("/orders/{orderId}/tracking")
    @Operation(summary = "Get shipment tracking")
    public ShipmentResponse tracking(@PathVariable Long orderId) {
        return deliveryService.tracking(orderId);
    }

    @PatchMapping("/orders/{orderId}/tracking")
    @Operation(summary = "Update simulated carrier status")
    public ShipmentResponse updateTrackingStatus(@PathVariable Long orderId,
                                                  @Valid @RequestBody ShipmentStatusRequest request) {
        return deliveryService.updateTrackingStatus(orderId, request.status());
    }
}
