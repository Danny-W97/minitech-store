package org.demo.productservice.product;

/** Product with its position in the complete catalog layout used for pricing. */
public record CatalogProduct(Product product, int index, int size, long layoutRevision) {
    public CatalogProduct(Product product, int index, int size) { this(product, index, size, 0); }
}
