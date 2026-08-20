package org.demo.reviewservice.web;

import java.math.BigDecimal;

public record ReviewSummaryResponse(Long productId, long publishedCount, BigDecimal averageRating) {}
