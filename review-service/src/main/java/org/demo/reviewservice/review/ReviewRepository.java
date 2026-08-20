package org.demo.reviewservice.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findAllByProductIdAndStatusOrderByCreatedAtDesc(Long productId, ReviewStatus status);
    List<Review> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Review> findAllByStatusOrderByCreatedAtAsc(ReviewStatus status);
    Optional<Review> findByCustomerIdAndOrderIdAndProductId(Long customerId, Long orderId, Long productId);
}
