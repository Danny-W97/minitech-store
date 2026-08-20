package org.demo.promotionservice;

import org.demo.promotionservice.promotion.PromotionCodeRepository;
import org.demo.promotionservice.promotion.PromotionService;
import org.demo.promotionservice.web.CreatePromotionCodeRequest;
import org.demo.promotionservice.web.PromotionRedemptionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PromotionServiceApplicationTests {
    @Autowired
    private PromotionService promotionService;

    @Autowired
    private PromotionCodeRepository promotionCodeRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void createsAutomaticallyGeneratedCode() {
        var response = promotionService.createCode(new CreatePromotionCodeRequest(
                null,
                "Kod testowy",
                12,
                5,
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(30),
                true
        ));

        assertThat(response.code()).startsWith("MINI-");
        assertThat(response.discountPercent()).isEqualTo(12);
        assertThat(response.maxRedemptions()).isEqualTo(5);
        assertThat(promotionCodeRepository.findByCodeIgnoreCase(response.code())).isPresent();
    }

    @Test
    void incrementsRedemptionOnlyOnceForTheSameOrder() {
        var created = promotionService.createCode(new CreatePromotionCodeRequest(
                "REDEEM-TEST",
                "Kod realizacji testowej",
                10,
                2,
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(30),
                true
        ));

        var first = promotionService.redeemCoupon(new PromotionRedemptionRequest(created.code(), 1001L));
        var repeated = promotionService.redeemCoupon(new PromotionRedemptionRequest(created.code(), 1001L));
        var secondOrder = promotionService.redeemCoupon(new PromotionRedemptionRequest(created.code(), 1002L));

        assertThat(first.redeemedCount()).isEqualTo(1);
        assertThat(repeated.redeemedCount()).isEqualTo(1);
        assertThat(secondOrder.redeemedCount()).isEqualTo(2);
        assertThat(promotionCodeRepository.findByCodeIgnoreCase(created.code()).orElseThrow().getRedeemedCount())
                .isEqualTo(2);
    }
}
