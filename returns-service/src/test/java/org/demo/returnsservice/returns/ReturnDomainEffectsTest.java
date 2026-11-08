package org.demo.returnsservice.returns;

import org.demo.returnsservice.web.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:return-domain-effects;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ReturnDomainEffectsTest {

    @Autowired ReturnService service;
    @Autowired ReturnRequestRepository cases;
    @MockitoSpyBean ReturnEventRepository events;
    @MockitoSpyBean ReturnOrderClient orders;
    @MockitoBean ReturnInventoryClient inventory;
    @MockitoBean ReturnBillingClient billing;
    @MockitoBean ReturnPaymentClient payment;

    private ReturnOrderClient.Snapshot snapshot() {
        return new ReturnOrderClient.Snapshot(
                501L, 42L, "customer@example.test", "COMPLETED", "PAID", false, new BigDecimal("100.00"),
                List.of(
                        new ReturnOrderClient.Line(11L, 1L, "Product one", 3, new BigDecimal("20.00")),
                        new ReturnOrderClient.Line(12L, 2L, "Product two", 1, new BigDecimal("40.00"))
                )
        );
    }

    @BeforeEach void clear() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        events.deleteAll();
        cases.deleteAll();
        doReturn(snapshot()).when(orders).get(anyLong(), anyLong(), anyString());
    }

    private Long createApprovedReturn() {
        var created = service.create(new CreateReturnRequest(
                501L, 42L, "customer@example.test", ReturnType.RETURN, "Reason", "Description"));
        service.changeStatus(created.id(), new ChangeReturnStatusRequest(
                ReturnStatus.UNDER_REVIEW, "Under review", 0L, UUID.randomUUID()));
        var approved = service.changeStatus(created.id(), new ChangeReturnStatusRequest(
                ReturnStatus.APPROVED, "Approved by operator", 1L, UUID.randomUUID()));
        assertThat(approved.status()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(approved.effectsState()).isEqualTo("DOMAIN_RECONCILIATION_REQUIRED");
        return created.id();
    }

    @Test void approvedReturnReconcilesDomainEffectsAndTransitionsToCompleted() {
        Long returnId = createApprovedReturn();

        // 1. Mock Inventory
        doReturn(new ReturnInventoryClient.RestockResponse(1L, UUID.randomUUID().toString(), 1L, "RECEIPT", 3, 10))
                .when(inventory).restock(eq(1L), eq(3), any(UUID.class));
        doReturn(new ReturnInventoryClient.RestockResponse(2L, UUID.randomUUID().toString(), 2L, "RECEIPT", 1, 5))
                .when(inventory).restock(eq(2L), eq(1), any(UUID.class));

        // 2. Mock Billing
        var invoice = new ReturnBillingClient.InvoiceSnapshot(
                10L, 501L, "FV/2026/001", new BigDecimal("100.00"), new BigDecimal("81.30"), new BigDecimal("18.70"),
                List.of(new ReturnBillingClient.InvoiceItem(1L, "P1", "Product one", 3, new BigDecimal("20.00"), new BigDecimal("60.00")),
                        new ReturnBillingClient.InvoiceItem(2L, "P2", "Product two", 1, new BigDecimal("40.00"), new BigDecimal("40.00"))),
                "PLN", "ISSUED"
        );
        doReturn(Optional.of(invoice)).when(billing).byOrder(501L);
        doReturn(new ReturnBillingClient.BalanceResponse(10L, new BigDecimal("100.00"), new BigDecimal("81.30"), new BigDecimal("18.70"), 0L))
                .when(billing).balance(10L);
        doReturn(new ReturnBillingClient.CorrectionResponse(1L, 10L, "KOR/2026/001", new BigDecimal("100.00"), new BigDecimal("-100.00"), BigDecimal.ZERO, 1L))
                .when(billing).issueCorrection(eq(10L), any(BigDecimal.class), anyString(), eq(0L), anyString());

        // 3. Mock Payment
        var paySnapshot = new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CARD", "cs_test_session_123"
        );
        doReturn(Optional.of(paySnapshot)).when(payment).byOrder(501L);
        doReturn(new ReturnPaymentClient.RefundResponse(
                50L, 100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "re_test_refund_123", "REFUND_COMPLETED"
        )).when(payment).refund(eq(100L), any(BigDecimal.class), anyString(), anyString());

        // Reconcile domain effects
        UUID opId = UUID.randomUUID();
        var result = service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, opId, "Zatwierdzone skutki zwrotu"));

        assertThat(result.status()).isEqualTo(ReturnStatus.COMPLETED);
        assertThat(result.effectsState()).isEqualTo("DOMAIN_RECONCILED");
        assertThat(result.version()).isEqualTo(3L);
        assertThat(result.moderationNote()).contains("[RECONCILED]", "KOR/2026/001", "re_test_refund_123");

        // Verify invocations
        verify(inventory, times(2)).restock(anyLong(), anyInt(), any(UUID.class));
        verify(billing, times(1)).issueCorrection(eq(10L), eq(new BigDecimal("-100.00")), anyString(), eq(0L), anyString());
        verify(payment, times(1)).refund(eq(100L), eq(new BigDecimal("100.00")), anyString(), eq("CUSTOMER_REQUEST"));

        // Verify audit event
        var eventsList = events.page(returnId, 1000L, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(eventsList).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("DOMAIN_EFFECTS_RECONCILED");
            assertThat(e.getFromStatus()).isEqualTo("APPROVED");
            assertThat(e.getToStatus()).isEqualTo("COMPLETED");
        });
    }

    @Test void idempotentReconciliationReplaysWithoutRepeatingExternalEffects() {
        Long returnId = createApprovedReturn();

        doReturn(new ReturnInventoryClient.RestockResponse(1L, UUID.randomUUID().toString(), 1L, "RECEIPT", 3, 10))
                .when(inventory).restock(anyLong(), anyInt(), any(UUID.class));
        doReturn(Optional.empty()).when(billing).byOrder(anyLong());
        var paySnapshot = new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CARD", "cs_test_session_123"
        );
        doReturn(Optional.of(paySnapshot)).when(payment).byOrder(501L);
        doReturn(new ReturnPaymentClient.RefundResponse(
                50L, 100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "re_test_refund_123", "REFUND_COMPLETED"
        )).when(payment).refund(eq(100L), eq(new BigDecimal("100.00")), anyString(), eq("CUSTOMER_REQUEST"));

        UUID opId = UUID.randomUUID();
        var first = service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, opId, "Pierwsze uzgodnienie"));
        assertThat(first.status()).isEqualTo(ReturnStatus.COMPLETED);

        // Replay
        var replay = service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, opId, "Pierwsze uzgodnienie"));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo(ReturnStatus.COMPLETED);

        // Inventory was called only once per item line during the first invocation
        verify(inventory, times(2)).restock(anyLong(), anyInt(), any(UUID.class));
        verify(payment, times(1)).refund(eq(100L), eq(new BigDecimal("100.00")), anyString(), eq("CUSTOMER_REQUEST"));
    }

    @Test void nonStripePaymentWithoutConfirmedRefundBlocksReconciliation() {
        Long returnId = createApprovedReturn();

        doReturn(new ReturnInventoryClient.RestockResponse(1L, UUID.randomUUID().toString(), 1L, "RECEIPT", 3, 10))
                .when(inventory).restock(anyLong(), anyInt(), any(UUID.class));
        doReturn(Optional.empty()).when(billing).byOrder(501L);
        var simPayment = new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CASH_ON_DELIVERY", null
        );
        doReturn(Optional.of(simPayment)).when(payment).byOrder(501L);

        UUID opId = UUID.randomUUID();
        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, opId, "Zwrot za pobraniem")))
                .hasMessageContaining("RETURN_NON_STRIPE_REFUND_UNCONFIRMED");

        var caseInDb = service.get(returnId);
        assertThat(caseInDb.status()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(caseInDb.effectsState()).isEqualTo("DOMAIN_RECONCILIATION_REQUIRED");
        // Verify no mutations were executed in inventory, billing or payment
        verify(inventory, never()).restock(anyLong(), anyInt(), any(UUID.class));
        verify(billing, never()).issueCorrection(anyLong(), any(), any(), anyLong(), any());
        verify(payment, never()).refund(anyLong(), any(), any(), any());
    }

    @Test void unconfirmedOrMissingPaymentBlocksReconciliationBeforeAnyMutation() {
        Long returnId = createApprovedReturn();

        // 1. Missing payment
        doReturn(Optional.empty()).when(payment).byOrder(501L);
        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Brak płatności")))
                .hasMessageContaining("RETURN_PAYMENT_NOT_FOUND");
        verify(inventory, never()).restock(anyLong(), anyInt(), any(UUID.class));
        verify(billing, never()).issueCorrection(anyLong(), any(), any(), anyLong(), any());

        // 2. Unsettled payment (PENDING)
        var pendingPayment = new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "PENDING", "CARD", "pi_pending", null, "https://example.test/checkout", null
        );
        doReturn(Optional.of(pendingPayment)).when(payment).byOrder(501L);
        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Płatność w toku")))
                .hasMessageContaining("RETURN_PAYMENT_NOT_SETTLED");
        verify(inventory, never()).restock(anyLong(), anyInt(), any(UUID.class));
        verify(billing, never()).issueCorrection(anyLong(), any(), any(), anyLong(), any());

        // 3. Insufficient invoice balance
        var settledPayment = new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CARD", "pi_settled", null, "https://example.test/checkout", null
        );
        doReturn(Optional.of(settledPayment)).when(payment).byOrder(501L);
        var invSnapshot = new ReturnBillingClient.InvoiceSnapshot(
                10L, 501L, "FV/2026/001", new BigDecimal("100.00"), new BigDecimal("81.30"), new BigDecimal("18.70"),
                List.of(), "PLN", "ISSUED"
        );
        doReturn(Optional.of(invSnapshot)).when(billing).byOrder(501L);
        doReturn(new ReturnBillingClient.BalanceResponse(10L, new BigDecimal("50.00"), new BigDecimal("40.65"), new BigDecimal("9.35"), 1L))
                .when(billing).balance(10L);

        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Za małe saldo faktury")))
                .hasMessageContaining("RETURN_INVOICE_BALANCE_INSUFFICIENT");
        verify(inventory, never()).restock(anyLong(), anyInt(), any(UUID.class));
        verify(billing, never()).issueCorrection(anyLong(), any(), any(), anyLong(), any());
    }

    @Test void unfinishedRefundStatusesBlockCompletionAndKeepReturnApproved() {
        Long returnId = createApprovedReturn();

        doReturn(new ReturnInventoryClient.RestockResponse(1L, UUID.randomUUID().toString(), 1L, "RECEIPT", 3, 10))
                .when(inventory).restock(anyLong(), anyInt(), any(UUID.class));
        doReturn(Optional.empty()).when(billing).byOrder(501L);
        var paySnapshot = new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CARD", "pi_fixture", null, "https://example.test/checkout", null
        );
        doReturn(Optional.of(paySnapshot)).when(payment).byOrder(501L);

        for (String status : List.of("REQUESTED", "PENDING", "FAILED", "REVIEW_REQUIRED")) {
            doReturn(new ReturnPaymentClient.RefundResponse(
                    50L, 100L, 501L, new BigDecimal("100.00"), "PLN", status, null, "PENDING"
            )).when(payment).refund(eq(100L), eq(new BigDecimal("100.00")), anyString(), eq("CUSTOMER_REQUEST"));

            assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Test " + status)))
                    .hasMessageContaining("RETURN_PAYMENT_REFUND_NOT_SETTLED");
            assertThat(service.get(returnId).status()).isEqualTo(ReturnStatus.APPROVED);
        }
    }

    @Test void retryAfterPartialFailureDoesNotDuplicateStockOrTriggerBillingKeyReuse() {
        Long returnId = createApprovedReturn();

        var seenRestockKeys = new HashSet<String>();
        var totalRestockedUnits = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            int qty = inv.getArgument(1);
            UUID key = inv.getArgument(2);
            if (seenRestockKeys.add(inv.getArgument(0) + ":" + key)) {
                totalRestockedUnits.addAndGet(qty);
            }
            return new ReturnInventoryClient.RestockResponse(1L, key.toString(), inv.getArgument(0), "RECEIPT", qty, totalRestockedUnits.get());
        }).when(inventory).restock(anyLong(), anyInt(), any(UUID.class));

        var invoice = new ReturnBillingClient.InvoiceSnapshot(
                10L, 501L, "FV/2026/001", new BigDecimal("100.00"), new BigDecimal("81.30"), new BigDecimal("18.70"),
                List.of(), "PLN", "ISSUED"
        );
        doReturn(Optional.of(invoice)).when(billing).byOrder(501L);
        var currentLedgerVersion = new java.util.concurrent.atomic.AtomicLong(0L);
        doAnswer(inv -> new ReturnBillingClient.BalanceResponse(
                10L, new BigDecimal("100.00"), new BigDecimal("81.30"), new BigDecimal("18.70"), currentLedgerVersion.get()
        )).when(billing).balance(10L);
        doAnswer(inv -> {
            long expectedVer = inv.getArgument(3);
            if (expectedVer != 0L) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "CORRECTION_KEY_REUSED");
            }
            currentLedgerVersion.set(1L);
            return new ReturnBillingClient.CorrectionResponse(
                    1L, 10L, "KOR/2026/001", new BigDecimal("100.00"), new BigDecimal("-100.00"), BigDecimal.ZERO, 1L);
        }).when(billing).issueCorrection(eq(10L), eq(new BigDecimal("-100.00")), anyString(), anyLong(), anyString());

        // Attempt 1: Payment refund fails after Inventory and Billing succeed
        doReturn(Optional.of(new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CARD", "cs_test_session_123"
        ))).when(payment).byOrder(501L);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Payment temporary outage"))
                .when(payment).refund(eq(100L), any(BigDecimal.class), anyString(), eq("CUSTOMER_REQUEST"));

        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Attempt 1")))
                .hasMessageContaining("Payment temporary outage");
        assertThat(totalRestockedUnits.get()).isEqualTo(4); // 3 + 1 units restocked once

        // Attempt 2 with a brand new operationId UUID: Payment succeeds, stock is NOT incremented again, Billing does not fail with CORRECTION_KEY_REUSED
        doReturn(Optional.of(new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CARD", "cs_test_session_123"
        ))).when(payment).byOrder(501L);
        doReturn(new ReturnPaymentClient.RefundResponse(
                50L, 100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "re_test_refund_123", "REFUND_COMPLETED"
        )).when(payment).refund(eq(100L), eq(new BigDecimal("100.00")), anyString(), eq("CUSTOMER_REQUEST"));

        var completed = service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Attempt 2"));
        assertThat(completed.status()).isEqualTo(ReturnStatus.COMPLETED);
        assertThat(totalRestockedUnits.get()).isEqualTo(4);
        assertThat(seenRestockKeys).hasSize(2);
    }

    @Test void missingAmountsOrUnallocatedOrderDiscountsBlockReconciliationBeforeMutatingInventory() {
        Long returnId = createApprovedReturn();

        // 1. Discounted order where sum(unitPrice * qty) = 100.00 != totalPrice (80.00)
        doReturn(new ReturnOrderClient.Snapshot(
                501L, 42L, "customer@example.test", "COMPLETED", "PAID", false, new BigDecimal("80.00"),
                List.of(
                        new ReturnOrderClient.Line(11L, 1L, "Product one", 3, new BigDecimal("20.00")),
                        new ReturnOrderClient.Line(12L, 2L, "Product two", 1, new BigDecimal("40.00"))
                )
        )).when(orders).get(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Discounted")))
                .hasMessageContaining("RETURN_ORDER_DISCOUNT_OR_SHIPPING_REQUIRES_MANUAL_SETTLEMENT");

        // 2. Missing line unitPrice must not fall back to order.totalPrice()
        doReturn(new ReturnOrderClient.Snapshot(
                501L, 42L, "customer@example.test", "COMPLETED", "PAID", false, new BigDecimal("100.00"),
                List.of(
                        new ReturnOrderClient.Line(11L, 1L, "Product one", 3, null),
                        new ReturnOrderClient.Line(12L, 2L, "Product two", 1, null)
                )
        )).when(orders).get(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Missing unit price")))
                .hasMessageContaining("RETURN_ORDER_SETTLEMENT_AMOUNTS_MISSING");

        // 3. Missing both unitPrice and totalPrice must not invent 10.00
        doReturn(new ReturnOrderClient.Snapshot(
                501L, 42L, "customer@example.test", "COMPLETED", "PAID", false, null,
                List.of(
                        new ReturnOrderClient.Line(11L, 1L, "Product one", 3, null),
                        new ReturnOrderClient.Line(12L, 2L, "Product two", 1, null)
                )
        )).when(orders).get(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, UUID.randomUUID(), "Missing all amounts")))
                .hasMessageContaining("RETURN_ORDER_SETTLEMENT_AMOUNTS_MISSING");

        verify(inventory, never()).restock(anyLong(), anyInt(), any());
        verify(payment, never()).refund(anyLong(), any(), any(), any());
    }

    @Test void reconciliationFailsAndRollsBackIfInventoryRestockFails() {
        Long returnId = createApprovedReturn();

        doReturn(Optional.of(new ReturnPaymentClient.PaymentSnapshot(
                100L, 501L, new BigDecimal("100.00"), "PLN", "SUCCEEDED", "CARD", "cs_test_session_123"
        ))).when(payment).byOrder(501L);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Inventory down"))
                .when(inventory).restock(anyLong(), anyInt(), any(UUID.class));

        UUID opId = UUID.randomUUID();
        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(2L, opId, "Test fail")))
                .hasMessageContaining("Inventory down");

        // Case in database remains in APPROVED status, not completed
        var caseInDb = service.get(returnId);
        assertThat(caseInDb.status()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(caseInDb.effectsState()).isEqualTo("DOMAIN_RECONCILIATION_REQUIRED");
    }

    @Test void reconciliationFailsIfCaseIsNotApproved() {
        var created = service.create(new CreateReturnRequest(
                501L, 42L, "customer@example.test", ReturnType.RETURN, "Reason", "Description"));

        // In REQUESTED status
        assertThatThrownBy(() -> service.reconcileEffects(created.id(), new ReconcileReturnRequest(0L, UUID.randomUUID(), "Note")))
                .hasMessageContaining("RETURN_STATUS_NOT_APPROVED");

        service.changeStatus(created.id(), new ChangeReturnStatusRequest(
                ReturnStatus.UNDER_REVIEW, "Reviewing", 0L, UUID.randomUUID()));

        // In UNDER_REVIEW status
        assertThatThrownBy(() -> service.reconcileEffects(created.id(), new ReconcileReturnRequest(1L, UUID.randomUUID(), "Note")))
                .hasMessageContaining("RETURN_STATUS_NOT_APPROVED");
    }

    @Test void reconciliationFailsOnVersionConflict() {
        Long returnId = createApprovedReturn();

        // Pass expectedVersion = 99L instead of 2L
        assertThatThrownBy(() -> service.reconcileEffects(returnId, new ReconcileReturnRequest(99L, UUID.randomUUID(), "Wrong version")))
                .hasMessageContaining("RETURN_VERSION_CONFLICT");
    }

    @Test void barePatchCompletedDirectlyStillBlocked() {
        Long returnId = createApprovedReturn();

        assertThatThrownBy(() -> service.changeStatus(returnId, new ChangeReturnStatusRequest(
                ReturnStatus.COMPLETED, "Direct completion", 2L, UUID.randomUUID())))
                .hasMessageContaining("RETURN_DOMAIN_EFFECTS_NOT_RECONCILED");
    }
}
