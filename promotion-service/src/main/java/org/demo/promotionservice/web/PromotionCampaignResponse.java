package org.demo.promotionservice.web;

import java.time.LocalDate;

public record PromotionCampaignResponse(String code, String name, int minDiscountPercent, int maxDiscountPercent,
                                        int promotionCount, int newProductCount, LocalDate validFrom,
                                        LocalDate validTo) {
}
