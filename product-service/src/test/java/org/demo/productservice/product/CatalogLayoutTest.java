package org.demo.productservice.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogLayoutTest {
    ProductRepository products = mock(ProductRepository.class);
    AtomicLong time = new AtomicLong();
    CatalogLayout layout = new CatalogLayout(products, 10, 3, time::get);
    Product selected = ProductOfferTest.product(7);

    @BeforeEach void readOnly() { TransactionSynchronizationManager.setCurrentTransactionReadOnly(true); }
    @AfterEach void clear() { TransactionSynchronizationManager.setCurrentTransactionReadOnly(false); }

    private ProductRepository.CatalogState state(long revision, long size) {
        return new State(revision, size);
    }

    private record State(long revision, long size) implements ProductRepository.CatalogState {
        public long getRevision() { return revision; }
        public long getProductCount() { return size; }
    }

    private void databasePosition() {
        var position = mock(ProductRepository.CatalogPosition.class);
        when(position.getProductId()).thenReturn(7L); when(position.getCatalogIndex()).thenReturn(1L);
        when(position.getCatalogSize()).thenReturn(3L);
        when(products.findCatalogPositions(List.of(7L))).thenReturn(List.of(position));
    }

    @Test void warmReadsReuseOnlyIdMetadataAndRespectExpiry() {
        when(products.findCatalogState()).thenReturn(state(1, 3));
        when(products.findCatalogIds(4)).thenReturn(List.of(3L, 7L, 9L));
        assertThat(layout.positions(List.of(selected)).getFirst().index()).isEqualTo(1);
        time.set(9_000_000); layout.positions(List.of(selected));
        verify(products, times(1)).findCatalogIds(4);
        time.set(10_000_000); layout.positions(List.of(selected));
        verify(products, times(2)).findCatalogIds(4);
        verify(products, never()).findCatalogPositions(anyList());
    }

    @Test void newRevisionInvalidatesLayoutEvenWhenCountHasNotChanged() {
        when(products.findCatalogState()).thenReturn(state(1, 3), state(2, 3));
        when(products.findCatalogIds(4)).thenReturn(List.of(3L, 7L, 9L), List.of(7L, 9L, 11L));
        assertThat(layout.positions(List.of(selected)).getFirst().index()).isEqualTo(1);
        assertThat(layout.positions(List.of(selected)).getFirst().index()).isZero();
    }

    @Test void olderReadTransactionDoesNotReplaceNewerCachedGeneration() {
        when(products.findCatalogState()).thenReturn(state(2, 2), state(1, 3), state(2, 2));
        when(products.findCatalogIds(4)).thenReturn(List.of(7L, 9L), List.of(3L, 7L, 9L));
        assertThat(layout.positions(List.of(selected)).getFirst().index()).isZero();
        assertThat(layout.positions(List.of(selected)).getFirst().index()).isEqualTo(1);
        assertThat(layout.positions(List.of(selected)).getFirst().index()).isZero();
        verify(products, times(2)).findCatalogIds(4);
    }

    @Test void writeTransactionsNeverPublishUncommittedProductIds() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        databasePosition(); layout.positions(List.of(selected));
        verify(products, never()).findCatalogState(); verify(products, never()).findCatalogIds(anyInt());
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        when(products.findCatalogState()).thenReturn(state(1, 3));
        when(products.findCatalogIds(4)).thenReturn(List.of(3L, 7L, 9L));
        layout.positions(List.of(selected));
        verify(products).findCatalogIds(4);
    }

    @Test void oversizedCatalogUsesBoundedDatabaseMetadataInsteadOfCachingAllIds() {
        when(products.findCatalogState()).thenReturn(state(1, 10000000));
        databasePosition(); assertThat(layout.positions(List.of(selected))).hasSize(1);
        verify(products, never()).findCatalogIds(anyInt());
    }
}
