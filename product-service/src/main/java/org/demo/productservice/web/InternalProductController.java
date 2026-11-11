package org.demo.productservice.web;

import org.demo.productservice.product.ProductService;
import org.demo.productservice.product.ProductPriceHistoryStore;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/products")
public class InternalProductController {
    private final ProductService products;
    private final ProductPriceHistoryStore priceHistory;

    public InternalProductController(ProductService products, ProductPriceHistoryStore priceHistory) {
        this.products = products; this.priceHistory = priceHistory;
    }

    @GetMapping("/batch")
    public List<ProductResponse> batch(@RequestParam List<Long> ids) { return products.getBatch(ids); }

    @GetMapping("/price-history/{productId}")
    public List<ProductPriceHistoryResponse> priceHistory(@PathVariable @Positive Long productId,
            @RequestParam(defaultValue = "100") int size) {
        return priceHistory.history(productId, size);
    }

    @GetMapping("/price-history/{productId}/lowest")
    public ProductPriceHistorySummary lowestPrice(@PathVariable @Positive Long productId,
            @RequestParam(defaultValue = "30") int days) {
        return priceHistory.lowest(productId, days);
    }
}
