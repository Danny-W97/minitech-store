package org.demo.productservice.product;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Provides a deterministic daily merchandising layer without storing mutable
 * presentation state in the product table. Every instance of Product Service
 * calculates the same offers for the same UTC day.
 */
@Component
public class DailyCatalogRotation {

    private static final int NEW_PRODUCT_COUNT = 8;
    private static final int PROMOTION_COUNT = 12;
    private static final int MIN_DISCOUNT_PERCENT = 5;
    private static final int MAX_DISCOUNT_PERCENT = 50;

    public ProductOffer offerFor(Product product, List<Product> catalog) {
        LocalDate rotationDate = LocalDate.now(ZoneOffset.UTC);
        int size = Math.max(catalog.size(), 1);
        int index = indexOf(product, catalog);
        long day = rotationDate.toEpochDay();

        int newOffset = Math.floorMod(day, size);
        int newDistance = Math.floorMod(index - newOffset, size);
        boolean newProduct = newDistance < Math.min(NEW_PRODUCT_COUNT, size);

        int promotionOffset = Math.floorMod(day * 3, size);
        int promotionDistance = Math.floorMod(index - promotionOffset, size);
        boolean promotional = promotionDistance < Math.min(PROMOTION_COUNT, size);
        int discountPercent = 0;
        if (promotional) {
            discountPercent = switch (promotionDistance) {
                case 0 -> MIN_DISCOUNT_PERCENT;
                case 1 -> MAX_DISCOUNT_PERCENT;
                default -> MIN_DISCOUNT_PERCENT
                        + Math.floorMod(day + product.getId() * 17, MAX_DISCOUNT_PERCENT - MIN_DISCOUNT_PERCENT + 1);
            };
        }

        BigDecimal regularPrice = product.getPrice().setScale(2, RoundingMode.HALF_UP);
        BigDecimal effectivePrice = promotional
                ? regularPrice.multiply(BigDecimal.valueOf(100L - discountPercent))
                .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP)
                : regularPrice;

        return new ProductOffer(
                effectivePrice,
                regularPrice,
                discountPercent,
                promotional,
                newProduct,
                rotationDate
        );
    }

    private int indexOf(Product product, List<Product> catalog) {
        for (int index = 0; index < catalog.size(); index++) {
            if (product.getId().equals(catalog.get(index).getId())) {
                return index;
            }
        }
        return 0;
    }

    public record ProductOffer(
            BigDecimal price,
            BigDecimal regularPrice,
            int discountPercent,
            boolean promotional,
            boolean newProduct,
            LocalDate rotationDate
    ) {
    }
}
