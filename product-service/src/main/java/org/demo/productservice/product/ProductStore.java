package org.demo.productservice.product;

import org.demo.productservice.web.CreateProductRequest;
import org.demo.productservice.web.PatchProductRequest;
import org.demo.productservice.web.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class ProductStore {
    private final ProductRepository productRepository;
    private final CatalogLayout catalogLayout;
    private final ProductPriceHistoryStore priceHistory;
    private final int historyMaxProducts;
    @Autowired
    public ProductStore(ProductRepository repository, CatalogLayout layout, ProductPriceHistoryStore priceHistory,
                        @Value("${price-history.snapshot.max-products:${catalog.layout.max-products:10000}}") int historyMaxProducts) {
        if (historyMaxProducts < 1 || historyMaxProducts > 100000) throw new IllegalArgumentException("Invalid price history catalog limit");
        productRepository=repository; catalogLayout=layout; this.priceHistory=priceHistory; this.historyMaxProducts=historyMaxProducts;
    }
    ProductStore(ProductRepository repository, CatalogLayout layout) {
        this(repository, layout, null, 10000);
    }
    @Transactional(readOnly = true)
    public List<CatalogProduct> getAll() {
        List<Product> catalog = productRepository.findAllByOrderByIdAsc();
        return catalogLayout.positions(catalog);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CatalogProduct> get(Long id) {
        Product product = productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        return withPositions(List.of(product));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CatalogProduct> getBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Provide between 1 and 100 positive product IDs");
        }
        Map<Long, Product> found = new LinkedHashMap<>();
        productRepository.findAllById(ids.stream().distinct().toList()).forEach(product -> found.put(product.getId(), product));
        // Missing IDs are omitted; the caller can identify the exact missing cart line.
        List<Product> selected = ids.stream().distinct().filter(found::containsKey).map(found::get).toList();
        return withPositions(selected);
    }

    private List<CatalogProduct> withPositions(List<Product> selected) {
        return catalogLayout.positions(selected);
    }

    @Transactional
    public List<CatalogProduct> create(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }
        if (priceHistory != null && productRepository.count() >= historyMaxProducts)
            throw new IllegalStateException("Product creation exceeds the configured price-history catalog limit");
        List<CatalogProduct> previousCatalog = priceHistory == null ? List.of() : getAll();

        Product product = new Product(
                request.sku(),
                request.name(),
                request.category(),
                request.manufacturer(),
                request.model(),
                parametersOrEmpty(request.technicalParameters()),
                request.price().setScale(2, RoundingMode.HALF_UP),
                request.active() == null || request.active(),
                request.imageUrl()
        );
        Product saved = productRepository.saveAndFlush(product);
        if (priceHistory == null) return withPositions(List.of(saved));
        List<CatalogProduct> catalog = getAll();
        if (catalog.size() > historyMaxProducts)
            throw new IllegalStateException("Product creation exceeds the configured price-history catalog limit");
        CatalogProduct created = catalog.stream().filter(entry -> entry.product().getId().equals(saved.getId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Created product is missing from catalog layout"));
        priceHistory.recordChange(created, "CREATE");
        priceHistory.recordCatalogLayoutChange(previousCatalog, catalog);
        return List.of(created);
    }

    @Transactional
    public List<CatalogProduct> patch(Long id, PatchProductRequest request) {
        if (request.isEmpty()) {
            throw new NoProductChangesException();
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        long previousPriceRevision = product.getPriceRevision();

        if (request.sku() != null && !request.sku().equals(product.getSku())
                && productRepository.existsBySkuAndIdNot(request.sku(), id)) {
            throw new DuplicateSkuException(request.sku());
        }

        product.update(
                request.sku(),
                request.name(),
                request.category(),
                request.manufacturer(),
                request.model(),
                request.technicalParameters(),
                request.price() == null ? null : request.price().setScale(2, RoundingMode.HALF_UP),
                request.active(),
                request.imageUrl()
        );
        Product saved = productRepository.saveAndFlush(product);
        List<CatalogProduct> positioned = withPositions(List.of(saved));
        if (priceHistory != null && saved.getPriceRevision() != previousPriceRevision)
            priceHistory.recordChange(positioned.getFirst(), "PRICE_CHANGE");
        return positioned;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int captureDailyPriceHistory(LocalDate date) {
        long count = productRepository.count();
        if (count > historyMaxProducts) throw new IllegalStateException("Price history snapshot exceeds configured catalog limit");
        List<CatalogProduct> catalog = getAll();
        return priceHistory == null ? 0 : priceHistory.recordDailySnapshot(catalog, date);
    }

    private Map<String, Object> parametersOrEmpty(Map<String, Object> parameters) {
        return parameters == null ? Map.of() : parameters;
    }

}
