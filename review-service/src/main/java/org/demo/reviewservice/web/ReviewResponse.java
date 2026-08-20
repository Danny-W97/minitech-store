package org.demo.reviewservice.web;

import org.demo.reviewservice.review.Review;
import org.demo.reviewservice.review.ReviewStatus;

import java.time.Instant;

public record ReviewResponse(
        Long id,
        Long customerId,
        Long orderId,
        Long productId,
        Integer rating,
        String title,
        String content,
        ReviewStatus status,
        String moderationNote,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReviewResponse from(Review review) {
        return new ReviewResponse(review.getId(), review.getCustomerId(), review.getOrderId(), review.getProductId(),
                review.getRating(), review.getTitle(), review.getContent(), review.getStatus(), review.getModerationNote(),
                review.getCreatedAt(), review.getUpdatedAt());
    }
}
