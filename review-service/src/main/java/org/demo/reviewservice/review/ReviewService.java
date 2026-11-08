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
        return published(productId, org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public ReviewSummaryResponse summary(Long productId) {
        var summary = repository.ratingSummary(productId);
        BigDecimal average = summary.getAverageRating() == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(summary.getAverageRating()).setScale(2, RoundingMode.HALF_UP);
        return new ReviewSummaryResponse(productId, summary.getReviewCount(), average);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> forCustomer(Long customerId) {
        return forCustomer(customerId, org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> pending() {
        return pending(org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<ReviewResponse> published(Long productId, org.demo.platformsecurity.ListWindow window) {
        return window.map(repository.page(window.ceiling(), productId, null, ReviewStatus.PUBLISHED, window.fetch()), Review::getId, ReviewResponse::from);
    }

    @Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<ReviewResponse> forCustomer(Long customerId, org.demo.platformsecurity.ListWindow window) {
        if (org.demo.platformsecurity.Access.request() != null) org.demo.platformsecurity.Access.customer(customerId);
        return window.map(repository.page(window.ceiling(), null, customerId, null, window.fetch()), Review::getId, ReviewResponse::from);
    }

    @Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<ReviewResponse> pending(org.demo.platformsecurity.ListWindow window) {
        return window.map(repository.pendingPage(window.cursor(), window.fetch()), Review::getId, ReviewResponse::from);
    }

    @Transactional
    public ReviewResponse create(CreateReviewRequest request) {
        if (repository.findByCustomerIdAndOrderIdAndProductId(request.customerId(), request.orderId(), request.productId()).isPresent()) {
            throw new ReviewException(HttpStatus.CONFLICT, "Dla tego produktu i zamówienia opinia już istnieje.");
        }
        OrderSnapshot order = orderClient.getOrder(request.orderId());
        boolean purchased = !order.lifecycleReviewRequired() && java.util.Set.of("PAID","SHIPPED","COMPLETED").contains(order.status()==null ? "" : order.status())
                && ("PAID".equals(order.paymentState()) || order.paymentState()==null)
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
