package org.demo.promotionservice.web;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LowestPriceResponse(Long productId, int days, LocalDate from, LocalDate to,
                                  BigDecimal lowestPrice) {
}
