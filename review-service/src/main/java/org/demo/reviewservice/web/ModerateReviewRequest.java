package org.demo.reviewservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.demo.reviewservice.review.ReviewStatus;

public record ModerateReviewRequest(
        @NotNull ReviewStatus status,
        @Size(max = 500) String note
) {}
