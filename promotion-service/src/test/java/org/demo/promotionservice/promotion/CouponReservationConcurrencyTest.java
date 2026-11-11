package org.demo.promotionservice.promotion;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:coupon-capacity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class CouponReservationConcurrencyTest {
    @Autowired CouponReservationService service;
    @Autowired CouponReservationStateStore states;
    @Autowired CouponReservationRepository holds;
    @Autowired PromotionCodeRepository codes;
    @Autowired PromotionRedemptionRepository redemptions;
    @Autowired PromotionService promotions;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clear() {
        redemptions.deleteAll();holds.deleteAll();codes.deleteAll();
        codes.saveAndFlush(new PromotionCode("LIMIT-ONE","Test",10,1,LocalDate.now(ZoneOffset.UTC).minusDays(1),LocalDate.now(ZoneOffset.UTC).plusDays(1),true));
    }
    private CouponReservationStateStore.Request request() { return new CouponReservationStateStore.Request("LIMIT-ONE",new BigDecimal("100"),new BigDecimal("10"),Instant.now(),Instant.now().plusSeconds(2400)); }
    @Test void twoConcurrentOrdersCannotReserveTheLastUseTwice() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> reserve(1L,start));var b=pool.submit(() -> reserve(2L,start));start.countDown();
            assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(states.capacity("LIMIT-ONE").reservedCount()).isEqualTo(1);assertThat(states.capacity("LIMIT-ONE").availableUses()).isZero();
    }
    @Test void twoCodesCannotOverwriteTheSameOrderReservationOnConcurrentFirstInsert() throws Exception {
        codes.saveAndFlush(new PromotionCode("OTHER-ONE","Other",10,1,LocalDate.now(ZoneOffset.UTC).minusDays(1),LocalDate.now(ZoneOffset.UTC).plusDays(1),true));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> firstCode("LIMIT-ONE",start));var b=pool.submit(() -> firstCode("OTHER-ONE",start));start.countDown();
            assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(holds.count()).isEqualTo(1);
        assertThat(states.capacity("LIMIT-ONE").reservedCount()+states.capacity("OTHER-ONE").reservedCount()).isEqualTo(1);
    }
    private boolean firstCode(String code,CountDownLatch start) throws Exception {
        start.await();var quote=request();
        try { service.reserve(1L,new CouponReservationStateStore.Request(code,quote.subtotal(),quote.discountAmount(),quote.acceptedAt(),quote.expiresAt()));return true; }
        catch(org.springframework.web.server.ResponseStatusException rejected) {assertThat(rejected.getStatusCode().value()).isEqualTo(409);return false;}
    }
    private boolean reserve(Long id,CountDownLatch start) throws Exception {
        start.await();try {service.reserve(id,request());return true;}
        catch(org.springframework.web.server.ResponseStatusException rejected) {assertThat(rejected.getStatusCode().value()).isEqualTo(409);return false;}
    }
    @Test void duplicateReservationAndRedemptionMoveOneUseFromReservedToConsumed() {
        var request=request();var first=service.reserve(1L,request);assertThat(service.reserve(1L,request)).isEqualTo(first);
        assertThat(states.redeem("LIMIT-ONE",1L).redeemedCount()).isEqualTo(1);
        assertThat(states.redeem("LIMIT-ONE",1L).redeemedCount()).isEqualTo(1);
        var capacity=states.capacity("LIMIT-ONE");assertThat(capacity.reservedCount()).isZero();assertThat(capacity.redeemedCount()).isEqualTo(1);
        assertThat(redemptions.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.reserve(2L,request())).hasMessageContaining("409");
    }
    @Test void releaseIsIdempotentAndFencesALateReservationForTheCancelledOrder() {
        service.reserve(1L,request());service.release(1L,"LIMIT-ONE");service.release(1L,"LIMIT-ONE");
        assertThatThrownBy(() -> service.reserve(1L,request())).hasMessageContaining("409");
        assertThat(service.reserve(2L,request()).state()).isEqualTo("RESERVED");
        assertThat(holds.count()).isEqualTo(2);
    }
    @Test void releaseBeforeReservationCreatesATombstoneAndNeverConsumesCapacity() {
        service.release(1L,"LIMIT-ONE");assertThatThrownBy(() -> service.reserve(1L,request())).hasMessageContaining("409");
        assertThat(states.capacity("LIMIT-ONE").availableUses()).isEqualTo(1);
    }
    @Test void reservedQuoteIsHonoredAfterCodeExpiryAndDeactivationButCannotBeReleasedAfterConsumption() {
        service.reserve(1L,request());
        jdbc.update("update promotion_codes set active=false,valid_to=? where code='LIMIT-ONE'",java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(1)));
        assertThat(states.redeem("LIMIT-ONE",1L).redeemed()).isTrue();
        assertThatThrownBy(() -> service.release(1L,"LIMIT-ONE")).hasMessageContaining("409");
        assertThat(states.get(1L).state()).isEqualTo("CONSUMED");
    }
    @Test void elapsedDeadlineNeverReleasesAQuoteWithoutCoordinatedOrderCancellation() {
        service.reserve(1L,request());jdbc.update("update coupon_reservations set expires_at=? where order_id=1",java.sql.Timestamp.from(Instant.EPOCH));
        assertThatThrownBy(() -> service.reserve(2L,request())).hasMessageContaining("409");
        assertThat(states.get(1L).state()).isEqualTo("RESERVED");assertThat(states.redeem("LIMIT-ONE",1L).redeemed()).isTrue();
    }
    @Test void changedAmountOrAnUnreservedRedemptionCannotConsumeAnyUse() {
        service.reserve(1L,request());
        assertThatThrownBy(() -> service.reserve(1L,new CouponReservationStateStore.Request("LIMIT-ONE",new BigDecimal("200"),new BigDecimal("20"),Instant.now(),null))).hasMessageContaining("409");
        assertThatThrownBy(() -> states.redeem("LIMIT-ONE",2L)).hasMessageContaining("409");
        assertThat(states.capacity("LIMIT-ONE").redeemedCount()).isZero();
    }
    @Test void publicValidationCountsHeldUsesButRemainsOnlyAPreview() {
        var request=new org.demo.promotionservice.web.CouponValidationRequest("LIMIT-ONE",new BigDecimal("100"));
        assertThat(promotions.validateCoupon(request).valid()).isTrue();service.reserve(1L,request());
        assertThat(promotions.validateCoupon(request).valid()).isFalse();
        service.release(1L,"LIMIT-ONE");assertThat(promotions.validateCoupon(request).valid()).isTrue();
    }
}
