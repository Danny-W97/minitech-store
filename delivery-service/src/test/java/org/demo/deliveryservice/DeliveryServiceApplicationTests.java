package org.demo.deliveryservice;

import org.demo.deliveryservice.delivery.DeliveryService;
import org.demo.deliveryservice.delivery.DeliveryMethod;
import org.demo.deliveryservice.delivery.DeliveryMethodRepository;
import org.demo.deliveryservice.web.DeliveryQuoteRequest;
import org.demo.deliveryservice.web.DeliveryQuoteResponse;
import org.demo.deliveryservice.web.ShipmentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DeliveryServiceApplicationTests {

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private DeliveryMethodRepository deliveryMethodRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void createsIdempotentTrackingAndUpdatesCarrierStatus() {
        ShipmentResponse created = deliveryService.ensureTracking(9001L, "PARCEL_LOCKER");
        ShipmentResponse repeated = deliveryService.ensureTracking(9001L, "PARCEL_LOCKER");
        ShipmentResponse updated = deliveryService.updateTrackingStatus(9001L, "IN_TRANSIT");

        assertThat(created.trackingNumber()).isEqualTo(repeated.trackingNumber());
        assertThat(created.carrier()).isEqualTo("MiniBox Locker");
        assertThat(updated.status()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void returnsAvailablePickupDatesAndValidatesSelectedDate() {
        deliveryMethodRepository.save(new DeliveryMethod(
                "STORE_PICKUP", "Odbiór osobisty", "Punkt MiniTech Store",
                BigDecimal.ZERO, null, 0, 1, true
        ));

        DeliveryQuoteResponse quote = deliveryService.quote(new DeliveryQuoteRequest(
                "STORE_PICKUP", "00-001", 1, new BigDecimal("100.00")
        ));

        assertThat(quote.fulfillmentType()).isEqualTo("STORE_PICKUP");
        assertThat(quote.availableDates()).isNotEmpty();
        assertThat(quote.selectedDate()).isEqualTo(quote.availableDates().getFirst());
        assertThat(quote.availableDates()).doesNotContain(java.time.LocalDate.now().plusDays(8));
    }

    @Test
    void chargesParcelLockerDeliveryRegardlessOfCartSubtotal() {
        deliveryMethodRepository.save(new DeliveryMethod(
                "PARCEL_LOCKER", "Paczkomat", "Automat paczkowy",
                new BigDecimal("11.99"), null, 1, 2, true
        ));

        DeliveryQuoteResponse quote = deliveryService.quote(new DeliveryQuoteRequest(
                "PARCEL_LOCKER", "90-999", 3, new BigDecimal("2439.98")
        ));

        assertThat(quote.deliveryCost()).isEqualByComparingTo("11.99");
        assertThat(quote.totalPrice()).isEqualByComparingTo("2451.97");
        assertThat(quote.freeDelivery()).isFalse();
    }
}
