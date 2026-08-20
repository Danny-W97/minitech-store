package org.demo.recommendationservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.demo.recommendationservice.recommendation.RecommendationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@RestController
@RequestMapping("/recommendations")
@Tag(name = "Recommendations", description = "Catalog and customer recommendations")
public class RecommendationController {
    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping("/products/{productId}")
    @Operation(summary = "Recommend similar products")
    public List<RecommendationResponse> forProduct(
            @PathVariable @Positive Long productId,
            @RequestParam(defaultValue = "4") @Min(1) @Max(12) int limit
    ) {
        return recommendationService.forProduct(productId, limit);
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Recommend products based on paid customer orders")
    public List<RecommendationResponse> forCustomer(
            @PathVariable @Positive Long customerId,
            @RequestParam(defaultValue = "6") @Min(1) @Max(12) int limit
    ) {
        return recommendationService.forCustomer(customerId, limit);
    }
}
