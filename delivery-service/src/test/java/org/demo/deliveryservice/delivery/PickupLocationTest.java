package org.demo.deliveryservice.delivery;

import org.demo.deliveryservice.web.DeliveryQuoteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PickupLocationTest {
    private final DeliveryMethodRepository methods = mock(DeliveryMethodRepository.class);
    private final PickupLocationRepository points = mock(PickupLocationRepository.class);
    private final DeliveryService service = new DeliveryService(methods, mock(ShipmentRepository.class), points);
    @BeforeEach void methods() {
        when(methods.findByCodeAndActiveTrue("STORE_PICKUP")).thenReturn(Optional.of(new DeliveryMethod(
                "STORE_PICKUP", "Pickup", "Pickup", BigDecimal.ZERO, null, 0, 1, true)));
        when(points.findAllByActiveTrueOrderByCodeAsc()).thenReturn(List.of(
                new PickupLocation("WARSAW_STORE", "MiniTech Store — Warszawa", true)));
    }
    private DeliveryQuoteRequest request(String point) {
        return new DeliveryQuoteRequest("STORE_PICKUP", "00-001", 1, BigDecimal.TEN, null, point);
    }
    @Test void codeAndLegacyLabelResolveToTheCanonicalServerRecord() {
        for (String selection : new String[] {"WARSAW_STORE", "MiniTech Store — Warszawa", null}) {
            var quote = service.quote(request(selection));
            assertEquals("WARSAW_STORE", quote.pickupLocationCode());
            assertEquals("MiniTech Store — Warszawa", quote.pickupLocation());
        }
    }
    @Test void rejectsUnknownAndInactiveLocations() {
        assertThrows(InvalidPickupLocationException.class, () -> service.quote(request("CLIENT_INVENTED_STORE")));
        assertThrows(InvalidPickupLocationException.class, () -> service.quote(request("CLOSED_STORE")));
    }
    @Test void noActiveLocationDisablesPickupInsteadOfUsingAnUnverifiedFallback() {
        when(points.findAllByActiveTrueOrderByCodeAsc()).thenReturn(List.of());
        assertThrows(InvalidPickupLocationException.class, () -> service.quote(request(null)));
    }
    @Test void rejectsPickupLocationForHomeDelivery() {
        when(methods.findByCodeAndActiveTrue("STANDARD_COURIER")).thenReturn(Optional.of(new DeliveryMethod(
                "STANDARD_COURIER", "Courier", "Courier", BigDecimal.TEN, null, 1, 2, true)));
        assertThrows(InvalidPickupLocationException.class, () -> service.quote(new DeliveryQuoteRequest(
                "STANDARD_COURIER", "00-001", 1, BigDecimal.TEN, null, "WARSAW_STORE")));
    }
}
