package org.demo.deliveryservice.delivery;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:shipment-sync;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ShipmentSynchronizationTest {
    @Autowired DeliveryService delivery;
    @Autowired ShipmentRepository shipments;
    @Autowired ShipmentSyncStateStore states;
    @Autowired ShipmentSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ShipmentOrderClient orders;
    @BeforeEach void clear() { shipments.deleteAll();reset(orders); }
    private void pending() { delivery.ensureTracking(101L,"STANDARD_COURIER");delivery.updateTrackingStatus(101L,"IN_TRANSIT"); }
    private ShipmentOrderClient.Result ack(ShipmentSyncStateStore.Ticket ticket) {
        return new ShipmentOrderClient.Result(ticket.orderId(),ticket.trackingNumber(),ticket.revision(),ticket.status(),"SHIPPED","PAID","APPLIED");
    }
    @Test void carrierProgressAndPendingSynchronizationCommitTogetherAndDoNotRegress() {
        pending();assertThat(states.status(101L).state()).isEqualTo("PENDING");assertThat(states.status(101L).revision()).isEqualTo(1);
        delivery.updateTrackingStatus(101L,"IN_TRANSIT");assertThat(states.status(101L).revision()).isEqualTo(1);
        delivery.updateTrackingStatus(101L,"DELIVERED");
        assertThatThrownBy(() -> delivery.updateTrackingStatus(101L,"IN_TRANSIT")).hasMessageContaining("409");
        assertThatThrownBy(() -> delivery.updateTrackingStatus(101L,"PICKED_UP")).hasMessageContaining("409");
        assertThat(states.status(101L).shipmentStatus()).isEqualTo("DELIVERED");
    }
    @Test void rollbackDoesNotLoseThePreviousCommittedCarrierState() {
        pending();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            delivery.updateTrackingStatus(101L,"DELIVERED");throw new IllegalStateException("rollback fixture");
        })).hasMessageContaining("rollback fixture");
        assertThat(states.status(101L).revision()).isEqualTo(1);assertThat(states.status(101L).shipmentStatus()).isEqualTo("IN_TRANSIT");
    }
    @Test void lostReplyRetriesTheSameVersionOutsideTheTransactionAndAcknowledgesOnlyItsOwnFact() {
        pending();
        var carrierUpdatedAt=delivery.tracking(101L).updatedAt();
        when(orders.apply(any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new org.springframework.web.client.ResourceAccessException("lost reply");
        });
        sync.process(101L);assertThat(states.status(101L).state()).isEqualTo("RETRY");
        doAnswer(call -> ack(call.getArgument(0))).when(orders).apply(any());sync.reconcile(101L);
        assertThat(states.status(101L).state()).isEqualTo("SYNCED");assertThat(states.status(101L).revision()).isEqualTo(1);
        assertThat(delivery.tracking(101L).updatedAt()).isEqualTo(carrierUpdatedAt);
        sync.reconcile(101L);verify(orders,times(2)).apply(any());
    }
    @Test void expiredLeaseCanBeRecoveredAndAnOldWorkerCannotAcknowledgeANewerRevision() {
        pending();var old=states.claim(101L);assertThat(states.claim(101L)).isNull();
        jdbc.update("update shipments set sync_until=? where order_id=101",java.sql.Timestamp.from(Instant.EPOCH));
        var current=states.claim(101L);states.result(old,"SYNCED",null);assertThat(states.status(101L).state()).isEqualTo("PENDING");
        delivery.updateTrackingStatus(101L,"DELIVERED");states.result(current,"SYNCED",null);
        assertThat(states.status(101L).state()).isEqualTo("PENDING");assertThat(states.status(101L).revision()).isEqualTo(2);
    }
    @Test void orderConflictIsVisibleForOperatorAndTemporaryReadinessRemainsRetryable() {
        pending();when(orders.apply(any())).thenThrow(new HttpClientErrorException(HttpStatus.TOO_EARLY));sync.process(101L);
        assertThat(states.status(101L).state()).isEqualTo("RETRY");
        doThrow(new HttpClientErrorException(HttpStatus.CONFLICT)).when(orders).apply(any());sync.reconcile(101L);
        assertThat(states.status(101L).state()).isEqualTo("REVIEW_REQUIRED");assertThat(states.status(101L).diagnosticCode()).isEqualTo("ORDER_HTTP_409");
    }
    @Test void mismatchedReceiptCannotBeDeclaredSynchronizedAndShippingMethodCannotBeReplaced() {
        pending();when(orders.apply(any())).thenReturn(new ShipmentOrderClient.Result(999L,"MT-FOREIGN",1,"IN_TRANSIT","SHIPPED","PAID","APPLIED"));
        sync.process(101L);assertThat(states.status(101L).state()).isEqualTo("REVIEW_REQUIRED");
        assertThatThrownBy(() -> delivery.ensureTracking(101L,"STORE_PICKUP")).hasMessageContaining("409");
    }
}
