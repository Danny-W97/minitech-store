package org.demo.inventoryservice.web;

import org.demo.inventoryservice.inventory.InventoryService;
import org.demo.inventoryservice.inventory.ReservationStatus;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryCheckoutExtensionAccessTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void operatorCannotExtendCheckoutReservation() {
        var service=new RecordingInventoryService();
        var controller=new InventoryController(service);
        installActor(new Actor("OPERATOR",null,null,"operator@example.test"));

        assertThatThrownBy(() -> controller.extendForCheckout(8L,
                new ExtendCheckoutReservationRequest(42L,Instant.now().plusSeconds(2400))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThat(service.calls).isZero();
    }

    @Test void onlyPaymentServiceCanUseCheckoutReservationExtension() {
        var service=new RecordingInventoryService();
        var controller=new InventoryController(service);
        installActor(new Actor("SERVICE","payment-service",null,null));
        var expires=Instant.now().plusSeconds(2400);
        var response=new ReservationResponse(8L,42L,ReservationStatus.RESERVED,expires,List.of());
        service.response=response;

        assertThat(controller.extendForCheckout(8L,new ExtendCheckoutReservationRequest(42L,expires))).isEqualTo(response);
        assertThat(service.calls).isEqualTo(1);
    }

    private static void installActor(Actor actor) {
        var request=new MockHttpServletRequest();
        request.setAttribute(Access.ACTOR,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static final class RecordingInventoryService extends InventoryService {
        private int calls;
        private ReservationResponse response;
        private RecordingInventoryService() { super(null,null,900); }
        @Override public ReservationResponse extendForCheckout(Long id,ExtendCheckoutReservationRequest request) {
            calls++;
            return response;
        }
    }
}
