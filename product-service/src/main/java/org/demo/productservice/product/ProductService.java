package org.demo.productservice.product;

import org.demo.productservice.web.CreateProductRequest;
import org.demo.productservice.web.PatchProductRequest;
import org.demo.productservice.web.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class ProductService {
    private final ProductStore store;
    private final DailyCatalogRotation dailyCatalogRotation;
    private final PromotionClient promotionClient;
    public ProductService(ProductStore store,DailyCatalogRotation dailyCatalogRotation,PromotionClient promotionClient) {
        this.store=store; this.dailyCatalogRotation=dailyCatalogRotation; this.promotionClient=promotionClient;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public List<ProductResponse> getAll() { return responses(store.getAll()); }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ProductResponse get(Long id) { return responses(store.get(id)).getFirst(); }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public List<ProductResponse> getBatch(List<Long> ids) { return responses(store.getBatch(ids)); }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ProductResponse create(CreateProductRequest request) { return responses(store.create(request)).getFirst(); }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ProductResponse patch(Long id,PatchProductRequest request) { return responses(store.patch(id,request)).getFirst(); }

    private List<ProductResponse> responses(List<CatalogProduct> selected) {
        if (selected.isEmpty()) return List.of();
        Map<Long, DailyCatalogRotation.ProductOffer> offers = promotionClient.evaluate(selected);
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        return selected.stream().map(entry -> toResponse(entry, offers, date)).toList();
    }

    private ProductResponse toResponse(CatalogProduct entry,
                                       Map<Long, DailyCatalogRotation.ProductOffer> offers, LocalDate date) {
        Product product = entry.product();
        DailyCatalogRotation.ProductOffer offer = offers.get(product.getId());
        if (offer == null) offer = dailyCatalogRotation.offerFor(product, entry.index(), entry.size(), date);
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getCategory(),
                product.getManufacturer(),
                product.getModel(),
                product.getTechnicalParameters(),
                offer.price(),
                offer.regularPrice(),
                offer.discountPercent(),
                offer.promotional(),
                offer.newProduct(),
                offer.rotationDate(),
                product.isActive(),
                product.getImageUrl(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
