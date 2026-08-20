package org.demo.recommendationservice.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "recommendation_requests")
public class RecommendationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "source_product_id")
    private Long sourceProductId;

    @Column(nullable = false, length = 40)
    private String strategy;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    protected RecommendationRequest() {}

    public RecommendationRequest(Long customerId, Long sourceProductId, String strategy, int resultCount) {
        this.customerId = customerId;
        this.sourceProductId = sourceProductId;
        this.strategy = strategy;
        this.resultCount = resultCount;
        this.requestedAt = Instant.now();
    }
}
