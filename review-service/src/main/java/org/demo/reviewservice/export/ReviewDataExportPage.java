package org.demo.reviewservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import org.demo.reviewservice.review.ReviewStatus;
import java.time.Instant;
import java.util.List;

/** Explicit customer-authored projection. Operator notes and order relationships are excluded. */
public record ReviewDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                   List<Item> items, boolean hasMore, String nextCursor,
                                   List<Omission> omissions) {
    @Schema(name = "ReviewExportItem")
    public record Item(Long reviewId, Long productId, int rating, String title, String content,
                       ReviewStatus status, Instant createdAt, Instant updatedAt) {}

    @Schema(name = "ReviewExportOmission")
    public record Omission(String scope, String status) {}
}
