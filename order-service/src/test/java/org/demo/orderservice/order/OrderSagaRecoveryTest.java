package org.demo.orderservice.order;

import org.demo.orderservice.inventory.InventoryClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderSagaRecoveryTest {

    @Test
    void scheduledRecoveryAppliesGraceWindowBeforeClaimingNewSagas() {
        OrderSagaService sagas = mock(OrderSagaService.class);
        OrderSagaRecovery recovery = new OrderSagaRecovery(sagas);

        Instant before = Instant.now();
        recovery.recover();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(sagas).resumeDueSagas(cutoff.capture());
        assertThat(cutoff.getValue())
                .isBetween(before.minusSeconds(2).minusMillis(50), after.minusSeconds(2).plusMillis(50));
    }

    @Test
    void immediateRecoveryUsesCurrentTimestampAndForwardsExplicitCutoff() {
        OrderSagaRepository repository = mock(OrderSagaRepository.class);
        OrderSagaStateStore states = mock(OrderSagaStateStore.class);
        InventoryClient inventory = mock(InventoryClient.class);
        when(repository.due(anyCollection(), any(Instant.class), any())).thenReturn(List.of());

        OrderSagaService service = new OrderSagaService(repository, states, inventory);
        try {
            Instant before = Instant.now();
            service.resumeDueSagas();
            Instant after = Instant.now();

            ArgumentCaptor<Instant> immediateCutoff = ArgumentCaptor.forClass(Instant.class);
            verify(repository).due(anyCollection(), immediateCutoff.capture(), any());
            assertThat(immediateCutoff.getValue()).isBetween(before, after);

            Instant explicit = Instant.parse("2026-11-15T12:00:00Z");
            service.resumeDueSagas(explicit);
            verify(repository).due(anyCollection(), org.mockito.ArgumentMatchers.eq(explicit), any());
        } finally {
            service.closeJobs();
        }
    }
}
