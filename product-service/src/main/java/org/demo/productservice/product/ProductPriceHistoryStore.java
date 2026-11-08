package org.demo.productservice.product;

import org.demo.productservice.web.ProductPriceHistoryResponse;
import org.demo.productservice.web.ProductPriceHistorySummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class ProductPriceHistoryStore {
    private final ProductPriceHistoryRepository history;
    private final DailyCatalogRotation rotation;

    public ProductPriceHistoryStore(ProductPriceHistoryRepository history, DailyCatalogRotation rotation) {
        this.history = history;
        this.rotation = rotation;
    }

    @Transactional
    public void recordChange(CatalogProduct product, String sourceType) {
        record(product, LocalDate.now(ZoneOffset.UTC), sourceType);
    }

    @Transactional
    public int recordDailySnapshot(List<CatalogProduct> products, LocalDate date) {
        if (products == null || products.isEmpty()) return 0;
        long layoutRevision = products.getFirst().layoutRevision();
        var existing = new java.util.HashSet<>(history.findProductIdsByRotationDateAndLayoutRevisionAndSourceType(
                date, layoutRevision, "DAILY"));
        var rows = products.stream().filter(entry -> !existing.contains(entry.product().getId()))
                .map(entry -> event(entry, date, "DAILY")).toList();
        history.saveAll(rows);
        return rows.size();
    }

    @Transactional
    public int recordCatalogLayoutChange(List<CatalogProduct> previous, List<CatalogProduct> current) {
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) return 0;
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        long layoutRevision = current.getFirst().layoutRevision();
        var existing = new java.util.HashSet<>(history.findProductIdsByRotationDateAndLayoutRevisionAndSourceType(
                date, layoutRevision, "CATALOG_LAYOUT_CHANGE"));
        var previousById = previous.stream().collect(java.util.stream.Collectors.toMap(
                entry -> entry.product().getId(), entry -> entry));
        var rows = current.stream().filter(entry -> {
                    var old = previousById.get(entry.product().getId());
                    if (old == null || existing.contains(entry.product().getId())) return false;
                    var oldOffer = rotation.offerFor(old.product(), old.index(), old.size(), date);
                    var newOffer = rotation.offerFor(entry.product(), entry.index(), entry.size(), date);
                    return oldOffer.price().compareTo(newOffer.price()) != 0;
                })
                .map(entry -> event(entry, date, "CATALOG_LAYOUT_CHANGE")).toList();
        history.saveAll(rows);
        return rows.size();
    }

    @Transactional(readOnly = true)
    public List<ProductPriceHistoryResponse> history(Long productId, int requestedSize) {
        int size = Math.min(Math.max(requestedSize, 1), 100);
        return history.findTop100ByProductIdOrderByRotationDateDescRecordedAtDescIdDesc(productId).stream()
                .limit(size).map(ProductPriceHistoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProductPriceHistorySummary lowest(Long productId, int requestedDays) {
        return lowest(productId, requestedDays, LocalDate.now(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    ProductPriceHistorySummary lowest(Long productId, int requestedDays, LocalDate to) {
        int days = Math.min(Math.max(requestedDays, 1), 30);
        LocalDate from = to.minusDays(days - 1L);
        return new ProductPriceHistorySummary(productId, days, from, to,
                history.findLowestEffectivePrice(productId, from, to).orElse(null),
                history.countObservedDays(productId, from, to), history.findObservedDates(productId, from, to));
    }

    private void record(CatalogProduct product, LocalDate date, String sourceType) {
        if (history.existsByProductIdAndRotationDateAndPriceRevisionAndLayoutRevisionAndSourceType(
                product.product().getId(), date, product.product().getPriceRevision(), product.layoutRevision(), sourceType)) return;
        history.save(event(product, date, sourceType));
    }

    private ProductPriceHistoryEvent event(CatalogProduct product, LocalDate date, String sourceType) {
        return new ProductPriceHistoryEvent(product,
                rotation.offerFor(product.product(), product.index(), product.size(), date), date, sourceType);
    }
}
