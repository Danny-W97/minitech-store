package org.demo.promotionservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.demo.promotionservice.promotion.PromotionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/promotions")
@Tag(name = "Promotions", description = "Daily promotions and price history")
public class PromotionController {
    private final PromotionService promotionService;
    public PromotionController(PromotionService promotionService) { this.promotionService = promotionService; }
    @GetMapping("/active")
    @Operation(summary = "List active promotion campaigns")
    public List<PromotionCampaignResponse> active() { return promotionService.activeCampaigns(); }
    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate daily offers for catalog products")
    public List<PromotionEvaluationResponse> evaluate(@RequestBody List<PromotionEvaluationRequest> requests) {
        return promotionService.evaluate(requests);
    }
    @GetMapping("/history/{productId}")
    @Operation(summary = "Show stored price history for a product")
    public List<PriceHistoryResponse> history(@PathVariable Long productId) { return promotionService.history(productId); }

    @GetMapping("/history/{productId}/lowest")
    @Operation(summary = "Show the lowest price when history is complete",
            description = "days is clamped to 1–30. lowestPrice is null and historyComplete is false until every date in the UTC window is observed.")
    public LowestPriceResponse lowestPrice(@PathVariable Long productId,
                                           @RequestParam(defaultValue = "30") int days) {
        return promotionService.lowestPrice(productId, days);
    }

    @GetMapping("/codes")
    @Operation(summary = "List promotion codes", description = "Lists codes for local administration and diagnostics")
    public List<PromotionCodeResponse> codes() { return promotionService.codes(); }

    @PostMapping("/codes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a promotion code", description = "Creates a local dev/test coupon; code may be omitted for automatic generation")
    public PromotionCodeResponse createCode(@Valid @RequestBody CreatePromotionCodeRequest request) {
        return promotionService.createCode(request);
    }

    @PostMapping("/codes/validate")
    @Operation(summary = "Validate a promotion code")
    public CouponValidationResponse validateCode(@Valid @RequestBody CouponValidationRequest request) {
        return promotionService.validateCoupon(request);
    }

    @PostMapping("/codes/redeem")
    @Operation(summary = "Redeem a promotion code after a successful payment")
    public PromotionRedemptionResponse redeemCode(@Valid @RequestBody PromotionRedemptionRequest request) {
        return promotionService.redeemCoupon(request);
    }
}
