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
    @Autowired private org.demo.promotionservice.promotion.CouponReservationService couponReservations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean private org.demo.promotionservice.promotion.CouponRedemptionVerifier couponVerifier;
    private void reserve(String code,Long id) {
        couponReservations.reserve(id,new org.demo.promotionservice.promotion.CouponReservationStateStore.Request(code,new java.math.BigDecimal("100.00"),new java.math.BigDecimal("10.00"),java.time.Instant.now(),java.time.Instant.now().plusSeconds(2400)));
    }
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

        reserve(created.code(),1001L);reserve(created.code(),1002L);
        var first = promotionService.redeemCoupon(new PromotionRedemptionRequest(created.code(), 1001L));
        var repeated = promotionService.redeemCoupon(new PromotionRedemptionRequest(created.code(), 1001L));
        var secondOrder = promotionService.redeemCoupon(new PromotionRedemptionRequest(created.code(), 1002L));

        assertThat(first.redeemedCount()).isEqualTo(1);
        assertThat(repeated.redeemedCount()).isEqualTo(1);
        assertThat(secondOrder.redeemedCount()).isEqualTo(2);
        assertThat(promotionCodeRepository.findByCodeIgnoreCase(created.code()).orElseThrow().getRedeemedCount())
                .isEqualTo(2);
    }

    @Test
    void concurrentRedemptionsAndDuplicateRetriesKeepAnExactCount() throws Exception {
        var created=promotionService.createCode(new CreatePromotionCodeRequest("A09-CONCURRENT","Concurrent",10,30,
                LocalDate.now().minusDays(1),LocalDate.now().plusDays(1),true));
        for(long id=2000;id<2020;id++) reserve(created.code(),id);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var results=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(long id=2000;id<2020;id++) {
                final long order=id;
                for(int repeat=0;repeat<2;repeat++) results.add(workers.submit(() -> promotionService.redeemCoupon(new PromotionRedemptionRequest(created.code(),order))));
            }
            for(var result:results) result.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(promotionCodeRepository.findByCodeIgnoreCase(created.code()).orElseThrow().getRedeemedCount()).isEqualTo(20);
    }
}
