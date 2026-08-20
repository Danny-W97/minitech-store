package org.demo.productservice.product;

import org.demo.productservice.web.CreateProductRequest;
import org.demo.productservice.web.PatchProductRequest;
import org.demo.productservice.web.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final DailyCatalogRotation dailyCatalogRotation;
    private final PromotionClient promotionClient;

    public ProductService(ProductRepository productRepository, DailyCatalogRotation dailyCatalogRotation,
                          PromotionClient promotionClient) {
        this.productRepository = productRepository;
        this.dailyCatalogRotation = dailyCatalogRotation;
        this.promotionClient = promotionClient;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAll() {
        List<Product> catalog = productRepository.findAllByOrderByIdAsc();
        Map<Long, DailyCatalogRotation.ProductOffer> offers = promotionClient.evaluate(catalog);
        return catalog.stream().map(product -> toResponse(product, catalog, offers)).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        List<Product> catalog = productRepository.findAllByOrderByIdAsc();
        Map<Long, DailyCatalogRotation.ProductOffer> offers = promotionClient.evaluate(catalog);
        return productRepository.findById(id)
                .map(product -> toResponse(product, catalog, offers))
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }

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
        Product saved = productRepository.save(product);
        List<Product> catalog = productRepository.findAllByOrderByIdAsc();
        return toResponse(saved, catalog, promotionClient.evaluate(catalog));
    }

    @Transactional
    public ProductResponse patch(Long id, PatchProductRequest request) {
        if (request.isEmpty()) {
            throw new NoProductChangesException();
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

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
        Product saved = productRepository.save(product);
        List<Product> catalog = productRepository.findAllByOrderByIdAsc();
        return toResponse(saved, catalog, promotionClient.evaluate(catalog));
    }

    private Map<String, Object> parametersOrEmpty(Map<String, Object> parameters) {
        return parameters == null ? Map.of() : parameters;
    }

    private ProductResponse toResponse(Product product, List<Product> catalog,
                                       Map<Long, DailyCatalogRotation.ProductOffer> offers) {
        DailyCatalogRotation.ProductOffer offer = offers.getOrDefault(
                product.getId(), dailyCatalogRotation.offerFor(product, catalog));
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
