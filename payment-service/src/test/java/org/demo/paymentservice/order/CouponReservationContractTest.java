package org.demo.paymentservice.order;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CouponReservationContractTest {
    @Test void acceptedReservationProofIsReadFromTheOrderWireContract() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var value=mapper.readValue("""
            {"id":700,"totalPrice":9,"status":"CONFIRMED","items":[],"inventoryReservationId":700,
             "deliveryMethod":"STANDARD_COURIER","deliveryCost":0,"promotionCode":"WELCOME10","discountAmount":1,
             "fulfillmentType":"HOME_DELIVERY","customerId":null,"customerEmail":"owner@example.test",
             "invoiceRequested":true,"lifecycleReviewRequired":false,"couponReservationVerified":true}
            """,OrderResponse.class);
        assertThat(value.couponReservationVerified()).isTrue();
        assertThat(value.lifecycleReviewRequired()).isFalse();
    }
    @Test void olderOrderResponseWithoutProofCanBeReadAndRequiresLiveVerification() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var value=mapper.readValue("""
            {"id":700,"totalPrice":9,"status":"CONFIRMED","items":[],"inventoryReservationId":700,
             "deliveryCost":0,"promotionCode":"WELCOME10","discountAmount":1,
             "invoiceRequested":false,"lifecycleReviewRequired":false}
            """,OrderResponse.class);
        assertThat(Boolean.TRUE.equals(value.couponReservationVerified())).isFalse();
    }
}
