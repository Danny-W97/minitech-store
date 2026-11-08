package org.demo.reviewservice.web;

import java.time.Instant;

/** Published reviews do not expose customer/order identifiers or moderation notes. */
public record PublicReviewResponse(Long id, Long productId, Integer rating, String title, String content, Instant createdAt) {
    public static PublicReviewResponse from(ReviewResponse review) {
        return new PublicReviewResponse(review.id(), review.productId(), review.rating(), review.title(), review.content(), review.createdAt());
    }
}
