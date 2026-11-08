package org.demo.reviewservice.review;

import org.demo.reviewservice.web.CreateReviewRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DeferredReviewTest {
    @Test void paidPurchaseAllowsAReviewBeforeAndAfterFulfillmentIncludingLegacySnapshots() {
        for(String status:List.of("PAID","SHIPPED","COMPLETED")) for(String financial:new String[]{"PAID",null}) {
            var repository=mock(ReviewRepository.class);var orders=mock(OrderClient.class);var service=new ReviewService(repository,orders);
            when(orders.getOrder(7L)).thenReturn(new OrderClient.OrderSnapshot(7L,List.of(new OrderClient.OrderItemSnapshot(1L)),status,17L,financial));
            when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
            assertThat(service.create(new CreateReviewRequest(17L,7L,1L,5,"Title","Content")).rating()).isEqualTo(5);
        }
    }
    @Test void financialRefundOrCancellationNeverGrantsReviewEligibility() {
        for(String financial:List.of("REFUNDED","REFUND_PENDING","COLLECTION_DUE")) {
            var repository=mock(ReviewRepository.class);var orders=mock(OrderClient.class);var service=new ReviewService(repository,orders);
            when(orders.getOrder(7L)).thenReturn(new OrderClient.OrderSnapshot(7L,List.of(new OrderClient.OrderItemSnapshot(1L)),"COMPLETED",17L,financial));
            assertThatThrownBy(() -> service.create(new CreateReviewRequest(17L,7L,1L,5,"Title","Content"))).hasMessageContaining("opłaconego");
            verify(repository,never()).save(any());
        }
        var repository=mock(ReviewRepository.class);var orders=mock(OrderClient.class);var service=new ReviewService(repository,orders);
        when(orders.getOrder(7L)).thenReturn(new OrderClient.OrderSnapshot(7L,List.of(new OrderClient.OrderItemSnapshot(1L)),"CANCELLED",17L,"PAID"));
        assertThatThrownBy(() -> service.create(new CreateReviewRequest(17L,7L,1L,5,"Title","Content"))).hasMessageContaining("opłaconego");
    }
    @Test void historicalPaidStatusUnderReviewDoesNotProvePurchase() {
        var repository=mock(ReviewRepository.class);var orders=mock(OrderClient.class);var service=new ReviewService(repository,orders);
        when(orders.getOrder(7L)).thenReturn(new OrderClient.OrderSnapshot(7L,List.of(new OrderClient.OrderItemSnapshot(1L)),"PAID",17L,"PAID",true));
        assertThatThrownBy(() -> service.create(new CreateReviewRequest(17L,7L,1L,5,"Title","Content"))).hasMessageContaining("opłaconego");
        verify(repository,never()).save(any());
    }
    @Test void fulfillmentCompletionIsNotProofOfPaymentForADeferredOrder() {
        var repository=mock(ReviewRepository.class);var orders=mock(OrderClient.class);
        var service=new ReviewService(repository,orders);
        when(orders.getOrder(7L)).thenReturn(new OrderClient.OrderSnapshot(7L,List.of(new OrderClient.OrderItemSnapshot(1L)),"COMPLETED",17L,"COLLECTION_DUE"));
        assertThatThrownBy(() -> service.create(new CreateReviewRequest(17L,7L,1L,5,"Title","Content")))
                .hasMessageContaining("opłaconego");
        verify(repository,never()).save(any());
    }
}
