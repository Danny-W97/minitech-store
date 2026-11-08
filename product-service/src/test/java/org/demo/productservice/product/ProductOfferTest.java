package org.demo.productservice.product;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductOfferTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final DailyCatalogRotation local = mock(DailyCatalogRotation.class);
    private final PromotionClient promotions = mock(PromotionClient.class);
    private final CatalogLayout layout = mock(CatalogLayout.class);
    private final ProductService service = new ProductService(new ProductStore(products,layout),local,promotions);
    private final LocalDate date = LocalDate.of(2026, 10, 24);

    @org.junit.jupiter.api.BeforeEach void catalogPositions() {
        when(layout.positions(anyList())).thenAnswer(invocation -> {
            List<Product> selected = invocation.getArgument(0);
            return java.util.stream.IntStream.range(0, selected.size())
                    .mapToObj(index -> new CatalogProduct(selected.get(index), index, selected.size(), 1)).toList();
        });
    }

    static Product product(long id) {
        var product = new Product("SKU-" + id, "Product", "TEST", "Brand", "Model", Map.of(), new BigDecimal("100.00"), true, "/unchanged.png");
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private DailyCatalogRotation.ProductOffer offer() {
        return new DailyCatalogRotation.ProductOffer(new BigDecimal("95.00"), new BigDecimal("100.00"), 5, true, false, date);
    }

    @Test void successfulRemoteOffersDoNotRunLocalFallback() {
        when(products.findAllByOrderByIdAsc()).thenReturn(List.of(product(7), product(8)));
        when(promotions.evaluate(anyList())).thenReturn(Map.of(7L, offer(), 8L, offer()));
        assertThat(service.getAll()).hasSize(2).allMatch(response -> response.price().equals(new BigDecimal("95.00")));
        verifyNoInteractions(local);
    }

    @Test void partialRemoteResponseOnlyFallsBackForMissingOfferWithItsGlobalIndex() {
        when(products.findAllByOrderByIdAsc()).thenReturn(List.of(product(7), product(8)));
        when(promotions.evaluate(anyList())).thenReturn(Map.of(7L, offer()));
        when(local.offerFor(any(), eq(1), eq(2), any())).thenReturn(offer());
        assertThat(service.getAll()).hasSize(2);
        verify(local).offerFor(argThat(product -> product.getId() == 8L), eq(1), eq(2), any());
        verifyNoMoreInteractions(local);
    }

    @Test void invalidBatchesDoNotTouchDatabaseOrDependencies() {
        for (var ids : List.of(List.<Long>of(), List.of(0L), java.util.Collections.nCopies(101, 1L))) {
            assertThatThrownBy(() -> service.getBatch(ids)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(products, promotions, local);
    }

    @Test void deterministicRotationMatchesKnownFullCatalogFormulaAcrossDatesAndIndices() {
        var rotation = new DailyCatalogRotation();
        var product = product(57);
        for (var current : List.of(date, date.plusDays(1), date.plusDays(40))) {
            for (int size : List.of(1, 8, 12, 1000)) {
                for (int index = 0; index < size; index++) {
                    long day = current.toEpochDay();
                    int distance = Math.floorMod(index - Math.floorMod(day * 3, size), size);
                    boolean promotional = distance < Math.min(12, size);
                    int discount = !promotional ? 0 : distance == 0 ? 5 : distance == 1 ? 50 : 5 + Math.floorMod(day + 57L * 17, 46);
                    var offer = rotation.offerFor(product, index, size, current);
                    assertThat(offer.discountPercent()).isEqualTo(discount);
                    assertThat(offer.price()).isEqualByComparingTo(BigDecimal.valueOf(100 - discount));
                    assertThat(offer.newProduct()).isEqualTo(Math.floorMod(index - Math.floorMod(day, size), size) < Math.min(8, size));
                }
            }
        }
    }
}
