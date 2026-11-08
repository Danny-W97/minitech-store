package org.demo.promotionservice.promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class CouponPricing {
    private CouponPricing() {}
    static BigDecimal discount(PromotionCode code,BigDecimal subtotal) {
        var discount=subtotal.multiply(BigDecimal.valueOf(code.getDiscountPercent())).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP);
        return code.getMaximumDiscountAmount()==null ? discount : discount.min(code.getMaximumDiscountAmount()).setScale(2,RoundingMode.HALF_UP);
    }
}
