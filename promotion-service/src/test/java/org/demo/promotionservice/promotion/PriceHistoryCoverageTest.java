package org.demo.promotionservice.promotion;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PriceHistoryCoverageTest {
    private final ProductPriceHistoryClient source = mock(ProductPriceHistoryClient.class);
    private final PromotionPriceHistoryRepository legacy = mock(PromotionPriceHistoryRepository.class);
    private final PromotionService service = new PromotionService(mock(PromotionCampaignRepository.class), legacy,
            mock(PromotionCodeRepository.class), mock(PromotionRedemptionRepository.class));

    private void summary(int observedDays) {
        ReflectionTestUtils.setField(service, "productPriceHistory", source);
        var today = LocalDate.now(java.time.ZoneOffset.UTC);
        when(source.lowest(7L, 30)).thenReturn(new ProductPriceHistoryClient.Summary(7L, 30,
                today.minusDays(29), today, new BigDecimal("19.00"), observedDays, List.of()));
    }

    @Test void incompleteAuthoritativeHistoryHidesAnObservedPriceAndDoesNotUseLegacyCoverage() {
        summary(29);
        var response = service.lowestPrice(7L, 30);
        assertThat(response.historyComplete()).isFalse();
        assertThat(response.lowestPrice()).isNull();
        assertThat(response.observedDays()).isEqualTo(29);
        verifyNoInteractions(legacy);
    }

    @Test void completeHistoryReturnsTheActualPriceAndSourceWindow() {
        summary(30);
        var response = service.lowestPrice(7L, 90);
        assertThat(response.historyComplete()).isTrue();
        assertThat(response.days()).isEqualTo(30);
        assertThat(response.lowestPrice()).isEqualByComparingTo("19.00");
        assertThat(response.from()).isEqualTo(response.to().minusDays(29));
    }

    @Test void sourceOutageCannotBeReportedAsZeroOrCompleteHistory() {
        summary(30);
        when(source.lowest(7L, 30)).thenThrow(new ResourceAccessException("isolated outage"));
        var response = service.lowestPrice(7L, 30);
        assertThat(response.historyComplete()).isFalse();
        assertThat(response.lowestPrice()).isNull();
        assertThat(response.observedDays()).isZero();
    }
}
