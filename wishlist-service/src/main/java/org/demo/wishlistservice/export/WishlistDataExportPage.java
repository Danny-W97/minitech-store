package org.demo.wishlistservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** A bounded page of saved products, not a complete account export. */
public record WishlistDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                     List<Item> items, boolean hasMore, String nextCursor,
                                     List<Omission> omissions) {
    @Schema(name = "WishlistExportItem")
    public record Item(Long productId, Instant createdAt) {}

    @Schema(name = "WishlistExportOmission")
    public record Omission(String scope, String status) {}
}
