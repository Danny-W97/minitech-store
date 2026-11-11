package org.demo.reviewservice.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findAllByProductIdAndStatusOrderByCreatedAtDesc(Long productId, ReviewStatus status);
    List<Review> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Review> findAllByStatusOrderByCreatedAtAsc(ReviewStatus status);

    @org.springframework.data.jpa.repository.Query("select r from Review r where r.id <= :ceiling "
            + "and (:productId is null or r.productId = :productId) and (:customerId is null or r.customerId = :customerId) "
            + "and (:status is null or r.status = :status) order by r.id desc")
    List<Review> page(@org.springframework.data.repository.query.Param("ceiling") long ceiling,
            @org.springframework.data.repository.query.Param("productId") Long productId,
            @org.springframework.data.repository.query.Param("customerId") Long customerId,
            @org.springframework.data.repository.query.Param("status") ReviewStatus status, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select r from Review r where r.id > :cursor "
            + "and r.status = org.demo.reviewservice.review.ReviewStatus.PENDING order by r.id asc")
    List<Review> pendingPage(@org.springframework.data.repository.query.Param("cursor") long cursor, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select count(r) as reviewCount, avg(r.rating) as averageRating from Review r "
            + "where r.productId = :productId and r.status = org.demo.reviewservice.review.ReviewStatus.PUBLISHED")
    RatingAggregate ratingSummary(@org.springframework.data.repository.query.Param("productId") Long productId);

    interface RatingAggregate { long getReviewCount(); Double getAverageRating(); }
    Optional<Review> findByCustomerIdAndOrderIdAndProductId(Long customerId, Long orderId, Long productId);
}
