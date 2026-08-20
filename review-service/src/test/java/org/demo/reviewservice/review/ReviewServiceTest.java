package org.demo.reviewservice.review;

import org.demo.reviewservice.web.CreateReviewRequest;
import org.demo.reviewservice.web.ReviewException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {
    @Mock
    private ReviewRepository repository;
    @Mock
    private OrderClient orderClient;
    @InjectMocks
    private ReviewService reviewService;

    @Test
    void createsPendingReviewForPaidCustomerOrder() {
        CreateReviewRequest request = new CreateReviewRequest(7L, 55L, 10L, 5, "Świetny produkt", "Działa bez zarzutu.");
        when(repository.findByCustomerIdAndOrderIdAndProductId(7L, 55L, 10L)).thenReturn(Optional.empty());
        when(orderClient.getOrder(55L)).thenReturn(new OrderClient.OrderSnapshot(
                55L, List.of(new OrderClient.OrderItemSnapshot(10L)), "PAID", 7L));
        when(repository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = reviewService.create(request);

        assertEquals(ReviewStatus.PENDING, result.status());
        assertEquals(5, result.rating());
        verify(repository).save(any(Review.class));
    }

    @Test
    void rejectsReviewWhenOrderIsNotPaidOrDoesNotContainProduct() {
        CreateReviewRequest request = new CreateReviewRequest(7L, 55L, 10L, 4, "Opinia", "Treść opinii.");
        when(repository.findByCustomerIdAndOrderIdAndProductId(7L, 55L, 10L)).thenReturn(Optional.empty());
        when(orderClient.getOrder(55L)).thenReturn(new OrderClient.OrderSnapshot(
                55L, List.of(new OrderClient.OrderItemSnapshot(99L)), "CONFIRMED", 7L));

        ReviewException exception = assertThrows(ReviewException.class, () -> reviewService.create(request));

        assertEquals(400, exception.status().value());
    }

    @Test
    void moderatesOnlyToPublishedOrRejected() {
        Review review = new Review(7L, 55L, 10L, 5, "Opinia", "Treść opinii.");
        when(repository.findById(1L)).thenReturn(Optional.of(review));

        var result = reviewService.moderate(1L, ReviewStatus.PUBLISHED, "Zaakceptowano");

        assertEquals(ReviewStatus.PUBLISHED, result.status());
        assertEquals("Zaakceptowano", result.moderationNote());
    }
}
