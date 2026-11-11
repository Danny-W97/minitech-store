package org.demo.reviewservice.web;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.OrderAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.demo.reviewservice.review.ReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/reviews")
@Tag(name = "Reviews", description = "Product reviews and moderation")
public class ReviewController {
    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) { this.reviewService = reviewService; }

    @GetMapping("/products/{productId}")
    @Operation(summary = "List published reviews for a product")
    public ResponseEntity<List<PublicReviewResponse>> published(@PathVariable @Positive Long productId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return reviewService.published(productId, new org.demo.platformsecurity.ListWindow(cursor, size)).map(PublicReviewResponse::from).response();
    }

    @GetMapping("/products/{productId}/summary")
    @Operation(summary = "Get product review summary")
    public ReviewSummaryResponse summary(@PathVariable @Positive Long productId) {
        return reviewService.summary(productId);
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "List reviews submitted by a customer")
    public ResponseEntity<List<ReviewResponse>> customer(@PathVariable @Positive Long customerId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        Access.customer(customerId);
        return reviewService.forCustomer(customerId, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @GetMapping("/moderation/pending")
    @Operation(summary = "List reviews pending moderation")
    public ResponseEntity<List<ReviewResponse>> pending(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return reviewService.pending(new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @PostMapping
    @Operation(summary = "Create a review after a paid purchase")
    public ResponseEntity<ReviewResponse> create(@Valid @RequestBody CreateReviewRequest request) {
        Access.customer(request.customerId());
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.create(request));
    }

    @PatchMapping("/{id}/moderation")
    @Operation(summary = "Publish or reject a review")
    public ReviewResponse moderate(@PathVariable @Positive Long id,
                                   @Valid @RequestBody ModerateReviewRequest request) {
        return reviewService.moderate(id, request.status(), request.note());
    }
}
