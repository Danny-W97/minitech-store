package org.demo.recommendationservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Existing retained account-linked log rows, not a complete activity history. */
public record RecommendationDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                           boolean recordingEnabled, String historyCoverage,
                                           List<Item> items, boolean hasMore, String nextCursor,
                                           List<Omission> omissions) {
    @Schema(name = "RecommendationExportItem")
    public record Item(Long requestId, Long sourceProductId, String strategy, int resultCount, Instant requestedAt) {}

    @Schema(name = "RecommendationExportOmission")
    public record Omission(String scope, String status) {}
}
