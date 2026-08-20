package org.demo.reviewservice.review;

import org.demo.reviewservice.review.OrderClient.OrderSnapshot;
import org.demo.reviewservice.web.CreateReviewRequest;
import org.demo.reviewservice.web.ReviewException;
import org.demo.reviewservice.web.ReviewResponse;
import org.demo.reviewservice.web.ReviewSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

@Service
public class ReviewService {
    private final ReviewRepository repository;
    private final OrderClient orderClient;

    public ReviewService(ReviewRepository repository, OrderClient orderClient) {
        this.repository = repository;
        this.orderClient = orderClient;
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> published(Long productId) {
        return repository.findAllByProductIdAndStatusOrderByCreatedAtDesc(productId, ReviewStatus.PUBLISHED)
                .stream().map(ReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReviewSummaryResponse summary(Long productId) {
        List<Review> reviews = repository.findAllByProductIdAndStatusOrderByCreatedAtDesc(productId, ReviewStatus.PUBLISHED);
        BigDecimal average = reviews.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(
                reviews.stream().mapToInt(Review::getRating).average().orElse(0)).setScale(2, RoundingMode.HALF_UP);
        return new ReviewSummaryResponse(productId, reviews.size(), average);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> forCustomer(Long customerId) {
        return repository.findAllByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream().map(ReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> pending() {
        return repository.findAllByStatusOrderByCreatedAtAsc(ReviewStatus.PENDING)
                .stream().map(ReviewResponse::from).toList();
    }

    @Transactional
    public ReviewResponse create(CreateReviewRequest request) {
        if (repository.findByCustomerIdAndOrderIdAndProductId(request.customerId(), request.orderId(), request.productId()).isPresent()) {
            throw new ReviewException(HttpStatus.CONFLICT, "Dla tego produktu i zamówienia opinia już istnieje.");
        }
        OrderSnapshot order = orderClient.getOrder(request.orderId());
        boolean purchased = "PAID".equals(order.status())
                && Objects.equals(order.customerId(), request.customerId())
                && order.items() != null
                && order.items().stream().anyMatch(item -> Objects.equals(item.productId(), request.productId()));
        if (!purchased) {
            throw new ReviewException(HttpStatus.BAD_REQUEST, "Opinię można dodać tylko do produktu z własnego opłaconego zamówienia.");
        }
        return ReviewResponse.from(repository.save(new Review(
                request.customerId(), request.orderId(), request.productId(), request.rating(), request.title(), request.content())));
    }

    @Transactional
    public ReviewResponse moderate(Long id, ReviewStatus status, String note) {
        if (status != ReviewStatus.PUBLISHED && status != ReviewStatus.REJECTED) {
            throw new ReviewException(HttpStatus.BAD_REQUEST, "Moderacja wymaga statusu PUBLISHED albo REJECTED.");
        }
        Review review = repository.findById(id)
                .orElseThrow(() -> new ReviewException(HttpStatus.NOT_FOUND, "Nie znaleziono opinii."));
        review.moderate(status, note);
        return ReviewResponse.from(review);
    }
}
