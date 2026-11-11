package org.demo.inventoryservice.inventory;

import org.demo.inventoryservice.web.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:stripe-stock;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","inventory.reservation.cleanup-fixed-delay-ms=3600000"})
class StripeStockCompensationTest {
    @Autowired InventoryService service;
    @Autowired InventoryReservationRepository reservations;
    @Autowired InventoryItemRepository items;
    @BeforeEach void clear() { reservations.deleteAll();items.deleteAll();service.createItem(new CreateInventoryItemRequest(101L,10)); }
    private Long reserve() { return service.reserve(new ReserveInventoryRequest(77L,List.of(new ReserveInventoryItemRequest(101L,3)))).reservationId(); }
    @Test void compensatedConfirmedSaleIsRestockedExactlyOnceAcrossConcurrentRetries() throws Exception {
        var id=reserve();service.confirm(id);
        assertThatThrownBy(() -> service.release(id)).isInstanceOf(InventoryReservationConflictException.class);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(() -> service.compensateStripe(id,77L)));
            for(var future:futures) future.get(10,TimeUnit.SECONDS);
        }
        var row=service.getItem(101L);assertThat(row.availableQuantity()).isEqualTo(10);assertThat(row.soldQuantity()).isZero();assertThat(row.reservedQuantity()).isZero();
        assertThat(service.getReservation(id).status()).isEqualTo(ReservationStatus.RELEASED);
    }
    @Test void reservedStockIsReleasedAndWrongOrderCannotCompensateIt() {
        var id=reserve();assertThatThrownBy(() -> service.compensateStripe(id,78L)).isInstanceOf(InventoryReservationConflictException.class);
        service.compensateStripe(id,77L);service.compensateStripe(id,77L);
        assertThat(service.getItem(101L).availableQuantity()).isEqualTo(10);
    }
}
