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
import org.mockito.Spy;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Test void unverifiedCouponIsCheckedBeforeCreatingAPaymentOrCallingAGateway() {
        var order=new OrderResponse(99L,java.util.List.of(),BigDecimal.TEN,"CONFIRMED",42L,null,"STANDARD_COURIER",BigDecimal.ZERO,"LIMIT-ONE",BigDecimal.ONE,null,null,null,null,false);
        when(orderClient.getOrder(99L)).thenReturn(order);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"missing reservation"))
                .when(promotionClient).requireReservation(order);
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(99L,"CARD",false))).hasMessageContaining("409");
        org.mockito.Mockito.verify(states,org.mockito.Mockito.never()).claim(any());
        org.mockito.Mockito.verify(paymentGateway,org.mockito.Mockito.never()).authorize(anyLong(),any(),any(),any(),any(),anyBoolean());
    }

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

    @Spy
    private PaymentGateway paymentGateway = new SimulationPaymentGateway();

    @InjectMocks
    private PaymentService paymentService;

    @Mock private PaymentStateStore states;
    @Spy private org.demo.paymentservice.followup.FollowUpPlanner followUps =
            new org.demo.paymentservice.followup.FollowUpPlanner(tools.jackson.databind.json.JsonMapper.builder().build());
    @Mock private org.demo.paymentservice.followup.FollowUpRunner followUpRunner;

    @org.junit.jupiter.api.BeforeEach
    void localStateDouble() {
        org.mockito.Mockito.lenient().when(states.lookup(any(),any())).thenAnswer(call -> new PaymentStateStore.Lookup(
                paymentRepository.findByOrderId(call.getArgument(0)).orElse(null),paymentRepository.findByIdempotencyKey(call.getArgument(1)).orElse(null)));
        org.mockito.Mockito.lenient().when(states.claim(any())).thenAnswer(call -> {
            Payment payment=call.getArgument(0);
            payment.claim("unit-owner",java.time.Instant.now().plusSeconds(120));
            paymentRepository.save(payment);
            return new PaymentStateStore.Ticket(payment,"unit-owner");
        });
        org.mockito.Mockito.lenient().doAnswer(call -> {
            PaymentStateStore.Ticket ticket=call.getArgument(0);
            org.demo.paymentservice.outbox.PaymentOutboxEvent event=call.getArgument(1);
            ticket.payment().release(); paymentRepository.save(ticket.payment());
            if (event!=null && outboxRepository.findByEventKey(event.getEventKey()).isEmpty()) outboxRepository.save(event);
            return ticket.payment();
        }).when(states).finish(any(),any());
        org.mockito.Mockito.lenient().when(states.finish(any(),any(),any())).thenAnswer(call -> {
            PaymentStateStore.Ticket ticket=call.getArgument(0);
            ticket.payment().release();paymentRepository.save(ticket.payment());return ticket.payment();
        });
        org.mockito.Mockito.lenient().doAnswer(call -> {
            PaymentStateStore.Ticket ticket=call.getArgument(0);
            ticket.payment().recordAuthorization(call.getArgument(1)); return null;
        }).when(states).recordAuthorization(any(),any());
    }

    @Test
    void unknownMethodIsRejectedBeforeAnySideEffects() {
        for (String method : new String[]{"MADE_UP", "", "CARDXYZ"}) {
            assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(11L, method, false)))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("400");
        }
        verifyNoInteractions(paymentRepository, orderClient, inventoryClient, outboxRepository, paymentGateway);
    }

    @Test
    void conflictingOrOversizedKeysAreRejectedBeforeLookupOrProvider() {
        var request=new CreatePaymentRequest(11L,"CARD",false,null,"body-key",null,false,null);
        assertThatThrownBy(() -> paymentService.create(request,"header-key"))
                .isInstanceOf(PaymentIdempotencyConflictException.class);
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(11L,"CARD",false),"x".repeat(101)))
                .hasMessageContaining("400");
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(11L,"CARD",false),"key\nvalue"))
                .hasMessageContaining("400");
        verifyNoInteractions(states,orderClient,inventoryClient,paymentGateway);
    }

    @Test
    void aChangedCommandCannotReplayAnExistingSuccess() {
        var original=new CreatePaymentRequest(11L,"CARD",false);
        var payment=new Payment(11L,BigDecimal.TEN,"CARD");
        payment.bindRequest(PaymentCommand.fingerprint(original,PaymentMethod.CARD));payment.markSucceeded("AUTH");
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.of(payment));
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(11L,"CARD",true)))
                .isInstanceOf(PaymentIdempotencyConflictException.class);
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(11L,"BLIK",false)))
                .isInstanceOf(PaymentIdempotencyConflictException.class);
        verifyNoInteractions(orderClient,inventoryClient,paymentGateway);
    }

    @Test
    void simulationHasTheSameAuthorizationAcrossIndependentAdapterInstances() {
        var first=new SimulationPaymentGateway().authorize(1L,BigDecimal.TEN,"PLN",PaymentMethod.CARD,"stable-key",false);
        var second=new SimulationPaymentGateway().authorize(1L,BigDecimal.TEN,"PLN",PaymentMethod.CARD,"stable-key",false);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void aStaleOwnerCannotReconcileInventoryEvenForAnAlreadyPaidOrder() {
        when(orderClient.getOrder(91L)).thenReturn(new OrderResponse(91L,BigDecimal.TEN,"PAID",991L,null));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT))
                .when(states).assertOwner(any());
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(91L,"CARD",false))).hasMessageContaining("409");
        verifyNoInteractions(inventoryClient,deliveryClient,promotionClient,billingClient);
    }

    @Test
    void unavailableProviderCannotCreatePaymentOrReconcileAnOrder() {
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(paymentGateway).requireAvailable();
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(11L, "CARD", false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("503");
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(orderClient, inventoryClient, outboxRepository);
    }

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
        verify(followUpRunner).runOrder(11L);
        verify(paymentGateway).authorize(11L, new BigDecimal("699.99"), "PLN", PaymentMethod.CARD, "order-11", false);
    }
    @Test void orderAwaitingLifecycleReviewCannotReachAuthorizationOrStock() {
        when(orderClient.getOrder(61L)).thenReturn(new OrderResponse(61L,java.util.List.of(),BigDecimal.TEN,"CONFIRMED",42L,null,
                "STANDARD_COURIER",BigDecimal.ZERO,null,BigDecimal.ZERO,"HOME_DELIVERY",null,null,null,false,17L,"owner@example.test",
                "UNPAID",null,null,true));
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(61L,"CARD",false))).hasMessageContaining("409");
        verify(paymentGateway,never()).authorize(anyLong(),any(),any(),any(),any(),anyBoolean());
        verify(states,never()).claim(any());verifyNoInteractions(inventoryClient,followUpRunner);
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
    void dependencyFailureAfterAuthorizationDoesNotBecomeADeclineOrReleaseStock() {
        when(orderClient.getOrder(71L)).thenReturn(new OrderResponse(71L,BigDecimal.TEN,"CONFIRMED",171L,null));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        when(orderClient.markPaid(71L)).thenThrow(new org.demo.paymentservice.order.OrderServiceException("Unavailable"));
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(71L,"CARD",false)))
                .isInstanceOf(PaymentProcessingException.class);
        var ticket=ArgumentCaptor.forClass(PaymentStateStore.Ticket.class);
        verify(states).release(ticket.capture());
        assertThat(ticket.getValue().payment().getProviderReference()).startsWith("SIM-");
        assertThat(ticket.getValue().payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(ticket.getValue().payment().isProviderDeclined()).isFalse();
        verify(orderClient,never()).cancel(any());verifyNoInteractions(inventoryClient);
        verify(states,never()).finish(any(),any());
    }

    @Test
    void cashOnDeliveryRemainsPendingUntilCollection() {
        when(paymentRepository.findByOrderId(12L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> { Payment payment=invocation.getArgument(0);org.springframework.test.util.ReflectionTestUtils.setField(payment,"id",12L);return payment; });
        when(orderClient.getOrder(12L))
                .thenReturn(new OrderResponse(12L,new BigDecimal("120.00"),"CONFIRMED",88L,null,"STANDARD_COURIER",null,"HOME_DELIVERY",null,null,null));
        when(orderClient.acceptDeferred(any(),any(),any(),any(),any())).thenReturn(new OrderResponse(12L,java.util.List.of(),new BigDecimal("120.00"),"CONFIRMED",88L,null,
                "STANDARD_COURIER",BigDecimal.ZERO,null,BigDecimal.ZERO,"HOME_DELIVERY",null,null,null,false,null,null,"COLLECTION_DUE","CASH_ON_DELIVERY",12L));

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(12L, "CASH_ON_DELIVERY", false));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        verify(orderClient, never()).markPaid(12L);
        verify(inventoryClient).confirmDeferred(12L,88L);
        verify(followUpRunner).runOrder(12L);
        verifyNoInteractions(paymentGateway);
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
        verify(followUpRunner).runOrder(31L);
    }
}
