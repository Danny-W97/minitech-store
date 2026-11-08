package org.demo.productservice.product;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Immutable, versioned ID metadata only. Prices and offers are never cached. */
@Component
public class CatalogLayout {
    private final ProductRepository products;
    private final long ttlNanos;
    private final int maxProducts;
    private final LongSupplier ticker;
    private volatile Snapshot cached;

    @Autowired
    public CatalogLayout(ProductRepository products,
                         @Value("${catalog.layout.ttl-ms:30000}") long ttlMs,
                         @Value("${catalog.layout.max-products:10000}") int maxProducts) {
        this(products, ttlMs, maxProducts, System::nanoTime);
    }

    CatalogLayout(ProductRepository products, long ttlMs, int maxProducts, LongSupplier ticker) {
        if (ttlMs < 0 || ttlMs > 3600000 || maxProducts < 1 || maxProducts > 1000000) {
            throw new IllegalArgumentException("Invalid catalog layout cache limits");
        }
        this.products = products;
        this.ttlNanos = ttlMs * 1000000;
        this.maxProducts = maxProducts;
        this.ticker = ticker;
    }

    public List<CatalogProduct> positions(List<Product> selected) {
        if (selected.isEmpty()) return List.of();
        // A rolled-back write may reuse its revision. Never publish uncommitted IDs.
        if (ttlNanos == 0 || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return databasePositions(selected);
        }
        var state = products.findCatalogState();
        if (state == null || state.getProductCount() > maxProducts) return databasePositions(selected);
        Snapshot snapshot = cached;
        if (!valid(snapshot, state)) {
            synchronized (this) {
                snapshot = cached;
                if (!valid(snapshot, state)) {
                    List<Long> ids = products.findCatalogIds(maxProducts + 1);
                    if (ids.size() != state.getProductCount() || ids.size() > maxProducts) return databasePositions(selected);
                    Map<Long, Integer> positions = new HashMap<>();
                    for (int index = 0; index < ids.size(); index++) positions.put(ids.get(index), index);
                    snapshot = new Snapshot(state.getRevision(), Map.copyOf(positions), ids.size(), ticker.getAsLong());
                    // An older repeatable-read transaction must not evict a newer generation.
                    if (cached == null || cached.revision() <= snapshot.revision()) cached = snapshot;
                }
            }
        }
        Snapshot current = snapshot;
        if (selected.stream().anyMatch(product -> !current.indices().containsKey(product.getId()))) return databasePositions(selected);
        return selected.stream().map(product -> new CatalogProduct(product, current.indices().get(product.getId()),
                current.size(), current.revision())).toList();
    }

    private boolean valid(Snapshot snapshot, ProductRepository.CatalogState state) {
        return snapshot != null && snapshot.revision() == state.getRevision()
                && snapshot.size() == state.getProductCount() && ticker.getAsLong() - snapshot.created() < ttlNanos;
    }

    private List<CatalogProduct> databasePositions(List<Product> selected) {
        Map<Long, ProductRepository.CatalogPosition> positions = new HashMap<>();
        products.findCatalogPositions(selected.stream().map(Product::getId).toList())
                .forEach(position -> positions.put(position.getProductId(), position));
        return selected.stream().map(product -> {
            var position = positions.get(product.getId());
            if (position == null) throw new IllegalStateException("Product catalog position is unavailable");
            return new CatalogProduct(product, Math.toIntExact(position.getCatalogIndex()),
                    Math.toIntExact(position.getCatalogSize()), position.getLayoutRevision());
        }).toList();
    }

    private record Snapshot(long revision, Map<Long, Integer> indices, int size, long created) {}
}
