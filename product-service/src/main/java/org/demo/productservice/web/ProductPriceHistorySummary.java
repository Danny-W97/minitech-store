package org.demo.productservice.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductPriceHistorySummary(Long productId, int days, LocalDate from, LocalDate to,
        BigDecimal lowestObservedPrice, int observedDays, List<LocalDate> observedDates) {}
