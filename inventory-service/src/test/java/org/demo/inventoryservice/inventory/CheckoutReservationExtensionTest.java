package org.demo.inventoryservice.inventory;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutReservationExtensionTest {
    @Test void onlyActiveReservationsCanBeExtended() {
        Instant now=Instant.parse("2026-10-25T12:00:00Z");
        var reservation=new InventoryReservation(42L,now.plusSeconds(900));
        reservation.extendForCheckout(now.plusSeconds(2400),now);
        assertThat(reservation.getExpiresAt()).isEqualTo(now.plusSeconds(2400));

        assertThatThrownBy(() -> reservation.extendForCheckout(now.plusSeconds(3000),now.plusSeconds(2400)))
                .isInstanceOf(InventoryReservationConflictException.class);
    }

    @Test void extensionMustMoveForwardAndCannotResurrectAnExpiredReservation() {
        Instant now=Instant.parse("2026-10-25T12:00:00Z");
        var active=new InventoryReservation(42L,now.plusSeconds(900));
        active.extendForCheckout(now.plusSeconds(800),now);
        assertThat(active.getExpiresAt()).isEqualTo(now.plusSeconds(900));

        var expired=new InventoryReservation(43L,now.minusSeconds(1));
        assertThatThrownBy(() -> expired.extendForCheckout(now.plusSeconds(2400),now))
                .isInstanceOf(InventoryReservationConflictException.class);
    }
}
