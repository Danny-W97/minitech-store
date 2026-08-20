package org.demo.paymentservice.payment;

import org.demo.paymentservice.inventory.InventoryClient;
import org.demo.paymentservice.billing.BillingClient;
import org.demo.paymentservice.delivery.DeliveryClient;
import org.demo.paymentservice.order.OrderClient;
import org.demo.paymentservice.order.OrderResponse;
import org.demo.paymentservice.outbox.PaymentOutboxRepository;
import org.demo.paymentservice.promotion.PromotionClient;
import org.demo.paymentservice.web.CreatePaymentRequest;
import org.demo.paymentservice.web.PaymentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderClient orderClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private PaymentOutboxRepository outboxRepository;

    @Mock
    private PromotionClient promotionClient;

    @Mock
    private DeliveryClient deliveryClient;

    @Mock
    private BillingClient billingClient;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void successfulPaymentMarksOrderAsPaid() {
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderClient.getOrder(11L))
                .thenReturn(new OrderResponse(11L, new BigDecimal("699.99"), "CONFIRMED", 77L, null));
        when(orderClient.markPaid(11L))
                .thenReturn(new OrderResponse(11L, new BigDecimal("699.99"), "PAID", 77L, null));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(11L, "CARD", false));

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(response.amount()).isEqualByComparingTo("699.99");
        assertThat(response.providerReference()).startsWith("SIM-");
        verify(orderClient).markPaid(11L);
        verify(inventoryClient).confirmWithRetry(77L, 3, 250);
        verify(promotionClient, never()).redeemCoupon(any(), any());
        verify(deliveryClient).ensureTracking(11L, null);
    }

    @Test
    void simulatedFailureIsPersistedWithoutChangingOrder() {
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderClient.getOrder(11L))
                .thenReturn(new OrderResponse(11L, new BigDecimal("699.99"), "CONFIRMED"));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(11L, "CARD", true));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureReason()).isEqualTo("Payment declined by the simulation");
        verify(orderClient, never()).markPaid(11L);
        verify(orderClient).cancel(11L);
    }

    @Test
    void cashOnDeliveryRemainsPendingUntilCollection() {
        when(paymentRepository.findByOrderId(12L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderClient.getOrder(12L))
                .thenReturn(new OrderResponse(12L, new BigDecimal("120.00"), "CONFIRMED", 88L, null));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(12L, "CASH_ON_DELIVERY", false));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        verify(orderClient, never()).markPaid(12L);
        verify(inventoryClient, never()).confirmWithRetry(any(), any(Integer.class), any(Long.class));
        verify(deliveryClient, never()).ensureTracking(any(), any());
    }

    @Test
    void successfulPaymentIsIdempotentForTheSameOrder() {
        Payment existingPayment = new Payment(11L, new BigDecimal("699.99"), "CARD");
        existingPayment.markSucceeded("SIM-12345678");
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.of(existingPayment));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(11L, "CARD", false));

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(response.providerReference()).isEqualTo("SIM-12345678");
        verify(orderClient, never()).getOrder(11L);
    }

    @Test
    void headerIdempotencyKeyIsUsedWhenBodyDoesNotContainOne() {
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());
        when(paymentRepository.findByIdempotencyKey("checkout-11")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderClient.getOrder(11L))
                .thenReturn(new OrderResponse(11L, new BigDecimal("699.99"), "CONFIRMED", 77L, null));
        when(orderClient.markPaid(11L))
                .thenReturn(new OrderResponse(11L, new BigDecimal("699.99"), "PAID", 77L, null));

        paymentService.create(new CreatePaymentRequest(11L, "CARD", false), "checkout-11");

        ArgumentCaptor<Payment> payments = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).save(payments.capture());
        assertThat(payments.getAllValues().getFirst().getIdempotencyKey()).isEqualTo("checkout-11");
    }

    @Test
    void alreadyPaidOrderReconcilesPaymentInsteadOfChargingAgain() {
        when(paymentRepository.findByOrderId(31L)).thenReturn(Optional.empty());
        when(paymentRepository.findByIdempotencyKey("retry-31")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderClient.getOrder(31L)).thenReturn(new OrderResponse(
                31L,
                new BigDecimal("2451.97"),
                "PAID",
                91L,
                null,
                "PARCEL_LOCKER",
                null,
                "HOME_DELIVERY",
                null,
                null,
                null
        ));

        PaymentResponse response = paymentService.create(
                new CreatePaymentRequest(31L, "CARD", false),
                "retry-31"
        );

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(response.amount()).isEqualByComparingTo("2451.97");
        assertThat(response.providerReference()).isEqualTo("RECOVERED-ORDER-31");
        verify(orderClient, never()).markPaid(31L);
        verify(inventoryClient).confirmWithRetry(91L, 3, 250);
        verify(deliveryClient).ensureTracking(31L, "PARCEL_LOCKER");
    }
}
