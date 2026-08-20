package org.demo.recommendationservice.web;

import org.demo.recommendationservice.recommendation.ProductCatalogClient.ProductSnapshot;

import java.math.BigDecimal;

public record RecommendationResponse(
        Long productId,
        String name,
        String category,
        String manufacturer,
        String model,
        BigDecimal price,
        BigDecimal regularPrice,
        int discountPercent,
        boolean promotional,
        boolean newProduct,
        String imageUrl,
        int score,
        String reason
) {
    public static RecommendationResponse from(ProductSnapshot product, int score, String reason) {
        return new RecommendationResponse(
                product.id(), product.name(), product.category(), product.manufacturer(), product.model(),
                product.price(), product.regularPrice(), product.discountPercent(), product.promotional(),
                product.newProduct(), product.imageUrl(), score, reason);
    }
}
