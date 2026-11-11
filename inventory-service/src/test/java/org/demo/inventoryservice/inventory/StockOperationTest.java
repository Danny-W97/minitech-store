package org.demo.inventoryservice.inventory;

import org.demo.inventoryservice.web.*;
import org.demo.platformsecurity.ListWindow;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a21;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=4","inventory.reservation.cleanup-fixed-delay-ms=3600000",
        "spring.jackson.deserialization.accept-float-as-int=false"})
class StockOperationTest {
    @Autowired InventoryService inventory;
    @Autowired StockOperationService service;
    @Autowired InventoryItemRepository items;
    @Autowired InventoryReservationRepository reservations;
    @MockitoSpyBean StockOperationRepository operations;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @BeforeEach void clear() {
        operations.deleteAll();reservations.deleteAll();items.deleteAll();
        inventory.createItem(new CreateInventoryItemRequest(101L,10));inventory.createItem(new CreateInventoryItemRequest(102L,10));
    }
    private String key() { return UUID.randomUUID().toString(); }
    private Long reserve(long orderId,int quantity) {
        return inventory.reserve(new ReserveInventoryRequest(orderId,List.of(new ReserveInventoryItemRequest(101L,quantity)))).reservationId();
    }
    @Test void twoReplicasOrThreadsReplayOneCommittedReceiptWithTheOriginalSnapshot() throws Exception {
        String id=key();var start=new CountDownLatch(1);var results=new ArrayList<Future<StockOperationResponse>>();
        try(var pool=Executors.newFixedThreadPool(8)) {
            for(int i=0;i<8;i++) results.add(pool.submit(() -> { start.await();return service.receive(101L,new ReceiveStockRequest(7,id)); }));
            start.countDown();var first=results.getFirst().get(15,TimeUnit.SECONDS);
            for(var result:results) assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(first);
            reserve(11,3);assertThat(service.receive(101L,new ReceiveStockRequest(7,id.toUpperCase(Locale.ROOT)))).isEqualTo(first);
            assertThat(service.get(id)).isEqualTo(first);assertThat(first.availableQuantity()).isEqualTo(17);
        }
        assertThat(inventory.getItem(101L).availableQuantity()).isEqualTo(14);assertThat(operations.count()).isEqualTo(1);
    }
    @Test void receivingConcurrentlyWithReservationPreservesBothDeltas() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var receipt=pool.submit(() -> { start.await();return service.receive(101L,new ReceiveStockRequest(5,key())); });
            var reservation=pool.submit(() -> { start.await();return reserve(12,4); });start.countDown();
            receipt.get(10,TimeUnit.SECONDS);reservation.get(10,TimeUnit.SECONDS);
        }
        var stock=inventory.getItem(101L);assertThat(stock.availableQuantity()).isEqualTo(11);
        assertThat(stock.reservedQuantity()).isEqualTo(4);assertThat(stock.version()).isEqualTo(2);
    }
    @Test void crossProductKeyCollisionRollsBackTheLosingStockUpdate() throws Exception {
        String id=key();var barrier=new CyclicBarrier(2);
        doAnswer(call -> {
            var result=entityManager.createQuery("select s from StockOperation s where s.operationId=:key",StockOperation.class)
                    .setParameter("key",id).getResultList().stream().findFirst();
            if(result.isEmpty() && TransactionSynchronizationManager.isActualTransactionActive()) barrier.await(5,TimeUnit.SECONDS);
            return result;
        }).when(operations).findByOperationId(id);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> receiveCode(101L,id));var second=pool.submit(() -> receiveCode(102L,id));
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(inventory.getItem(101L).availableQuantity()+inventory.getItem(102L).availableQuantity()).isEqualTo(23);
        assertThat(operations.count()).isEqualTo(1);
    }
    private int receiveCode(long product,String id) {
        try { service.receive(product,new ReceiveStockRequest(3,id));return 201; }
        catch(org.springframework.web.server.ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
    }
    @Test void stocktakeRequiresReadVersionAndReplaysAfterFurtherStockChanges() {
        var old=inventory.getItem(101L);var reservation=reserve(13,2);
        assertThatThrownBy(() -> service.adjust(101L,new PatchInventoryItemRequest(20,old.version(),key()))).hasMessageContaining("409");
        assertThat(operations.count()).isZero();
        String id=key();var request=new PatchInventoryItemRequest(5,inventory.getItem(101L).version(),id);
        var corrected=service.adjust(101L,request);assertThat(corrected.quantity()).isEqualTo(-3);
        inventory.release(reservation);
        assertThat(service.adjust(101L,request)).isEqualTo(corrected);
        assertThat(inventory.getItem(101L).availableQuantity()).isEqualTo(7);
        assertThatThrownBy(() -> service.adjust(101L,new PatchInventoryItemRequest(6,request.expectedVersion(),id))).hasMessageContaining("409");
        assertThat(operations.count()).isEqualTo(1);
    }
    @Test void failureWritingLedgerRollsBackFlushedStockAndItsVersion() {
        doThrow(new IllegalStateException("Simulated ledger outage")).when(operations).saveAndFlush(any(StockOperation.class));
        assertThatThrownBy(() -> service.receive(101L,new ReceiveStockRequest(5,key()))).isInstanceOf(IllegalStateException.class);
        assertThat(inventory.getItem(101L).availableQuantity()).isEqualTo(10);
        assertThat(inventory.getItem(101L).version()).isZero();assertThat(operations.count()).isZero();
    }
    @Test void incomingStockCannotMakeReservedStockOverflowOnRelease() {
        reserve(14,2);
        assertThatThrownBy(() -> service.receive(101L,new ReceiveStockRequest(Integer.MAX_VALUE,key()))).hasMessageContaining("capacity");
        assertThat(inventory.getItem(101L).availableQuantity()).isEqualTo(8);assertThat(operations.count()).isZero();
        assertThatThrownBy(() -> service.receive(101L,new ReceiveStockRequest(0,key()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.receive(101L,new ReceiveStockRequest(1,"../bad"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fractionalQuantitiesAndVersionsAreRejectedDuringJsonParsing() {
        String id=key();
        assertThatThrownBy(() -> json.readValue("{\"quantity\":1.5,\"operationId\":\""+id+"\"}",ReceiveStockRequest.class)).hasMessageContaining("quantity");
        assertThatThrownBy(() -> json.readValue("{\"availableQuantity\":10,\"expectedVersion\":1.5,\"operationId\":\""+id+"\"}",PatchInventoryItemRequest.class)).hasMessageContaining("expectedVersion");
        assertThat(operations.count()).isZero();
    }
    @Test void ledgerIsBoundedAndChangedCommandsCannotReuseAnOperationId() {
        String id=key();service.receive(101L,new ReceiveStockRequest(1,id));
        assertThatThrownBy(() -> service.receive(101L,new ReceiveStockRequest(2,id))).hasMessageContaining("409");
        assertThatThrownBy(() -> service.adjust(101L,new PatchInventoryItemRequest(11,0L,id))).hasMessageContaining("409");
        for(int i=0;i<4;i++) service.receive(101L,new ReceiveStockRequest(1,key()));
        service.receive(102L,new ReceiveStockRequest(1,key()));
        var first=service.list(101L,new ListWindow(0,2));var second=service.list(101L,new ListWindow(Long.parseLong(first.nextCursor()),2));
        assertThat(first.items()).hasSize(2);assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).hasSize(2).noneMatch(row -> first.items().stream().anyMatch(previous -> previous.id().equals(row.id())));
    }
}
