package org.demo.returnsservice.returns;

import org.demo.returnsservice.web.ReturnResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReturnDomainEffectsService {
    public record Outcome(List<String> restockReceipts, String billingCorrection, String paymentRefund, String summary) {}

    private final ReturnInventoryClient inventory;
    private final ReturnBillingClient billing;
    private final ReturnPaymentClient payment;
    private final Set<Long> inFlightCases = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> recordedInvoiceExpectedVersions = new ConcurrentHashMap<>();
    private final Map<Long, String> recordedCorrectionOutcomes = new ConcurrentHashMap<>();

    public ReturnDomainEffectsService(ReturnInventoryClient inventory, ReturnBillingClient billing, ReturnPaymentClient payment) {
        this.inventory = inventory;
        this.billing = billing;
        this.payment = payment;
    }

    public Outcome reconcile(ReturnResponse request, ReturnOrderClient.Snapshot order, UUID operationId, String note) {
        if (request == null || request.id() == null || request.id() <= 0) {
            throw ReturnIntent.bad("RETURN_INVALID_CASE");
        }
        if (request.status() != ReturnStatus.APPROVED) {
            throw ReturnIntent.conflict("RETURN_STATUS_NOT_APPROVED");
        }
        if (request.requestType() != ReturnType.RETURN) {
            throw ReturnIntent.conflict("RETURN_COMPLAINT_REQUIRES_MANUAL_RESOLUTION");
        }
        if (operationId == null) {
            throw ReturnIntent.bad("RETURN_OPERATION_ID_REQUIRED");
        }
        // Validate complete settlement amounts before executing any external mutation (R04)
        BigDecimal returnAmount = calculateVerifiedRefundAmount(request, order);

        // Phase 1A: Payment Settlement Pre-check (must precede ANY mutation)
        var paymentOpt = payment.byOrder(request.orderId());
        if (paymentOpt.isEmpty()) {
            throw ReturnIntent.conflict("RETURN_PAYMENT_NOT_FOUND");
        }
        var p = paymentOpt.get();
        if (p.id() == null) {
            throw ReturnIntent.conflict("RETURN_PAYMENT_UNVERIFIED");
        }
        if (!("SUCCEEDED".equals(p.status()) || "REFUNDED".equals(p.status()))) {
            throw ReturnIntent.conflict("RETURN_PAYMENT_NOT_SETTLED");
        }
        if (p.amount() != null && returnAmount.compareTo(p.amount()) > 0) {
            throw ReturnIntent.conflict("RETURN_REFUND_EXCEEDS_PAYMENT_AMOUNT");
        }
        boolean stripeCardEligible = p.checkoutSessionId() != null
                || "CARD".equalsIgnoreCase(p.paymentMethod())
                || "STRIPE".equalsIgnoreCase(p.paymentMethod())
                || (p.providerReference() != null && (p.providerReference().startsWith("pi_") || p.providerReference().startsWith("cs_")))
                || p.checkoutUrl() != null;
        if (!stripeCardEligible) {
            throw ReturnIntent.conflict("RETURN_NON_STRIPE_REFUND_UNCONFIRMED");
        }

        // Phase 1B: Billing Balance Pre-check (read-only verification before ANY mutation)
        var invoiceOpt = billing.byOrder(request.orderId());
        ReturnBillingClient.InvoiceSnapshot invoice = null;
        Long expectedVersion = null;
        if (invoiceOpt.isPresent()) {
            invoice = invoiceOpt.get();
            if (invoice.id() == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_INVOICE_UNVERIFIED");
            }
            String versionKey = request.id() + ":" + invoice.id();
            expectedVersion = recordedInvoiceExpectedVersions.get(versionKey);
            if (expectedVersion == null) {
                var balance = billing.balance(invoice.id());
                if (balance == null) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_BALANCE_UNAVAILABLE");
                }
                if (balance.grossAmount() != null && balance.grossAmount().compareTo(returnAmount) < 0) {
                    throw ReturnIntent.conflict("RETURN_INVOICE_BALANCE_INSUFFICIENT");
                }
                expectedVersion = balance.version();
                recordedInvoiceExpectedVersions.putIfAbsent(versionKey, expectedVersion);
                expectedVersion = recordedInvoiceExpectedVersions.get(versionKey);
            }
        }

        if (!inFlightCases.add(request.id())) {
            throw ReturnIntent.conflict("RETURN_EFFECTS_RECONCILIATION_IN_PROGRESS");
        }
        try {
            // 1. Stock Restock in inventory-service using deterministic per-case-and-line key (R03)
            List<String> restockReceipts = new ArrayList<>();
            for (var item : request.items()) {
                UUID restockOpId = UUID.nameUUIDFromBytes(
                        ("return-restock-v1:" + request.id() + ":" + item.orderItemId()).getBytes(StandardCharsets.UTF_8));
                var restock = inventory.restock(item.productId(), item.quantity(), restockOpId);
                if (restock == null || restock.id() == null || restock.operationId() == null) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INVENTORY_RESTOCK_UNCONFIRMED");
                }
                restockReceipts.add("PROD-" + item.productId() + ":qty=" + item.quantity() + ":op=" + restock.operationId());
            }

            // 2. Billing Invoice Correction in billing-service using deterministic per-case key and preserved expectedVersion (R03)
            String billingCorrection = recordedCorrectionOutcomes.get(request.id());
            if (billingCorrection == null) {
                if (invoice != null) {
                    BigDecimal corrDelta = returnAmount.negate();
                    String corrKey = "return-corr-v1-" + request.id();
                    String corrReason = "Zwrot #" + request.id() + ": " + (note == null || note.isBlank() ? "Rozliczenie" : note.trim());
                    ReturnBillingClient.CorrectionResponse correction;
                    try {
                        correction = billing.issueCorrection(invoice.id(), corrDelta, corrKey, expectedVersion, corrReason);
                    } catch (ResponseStatusException conflict) {
                        if (conflict.getStatusCode().value() == 409 && expectedVersion > 0
                                && conflict.getReason() != null && conflict.getReason().contains("CORRECTION_KEY_REUSED")) {
                            correction = billing.issueCorrection(invoice.id(), corrDelta, corrKey, expectedVersion - 1, corrReason);
                        } else {
                            throw conflict;
                        }
                    }
                    if (correction == null || correction.id() == null) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_CORRECTION_UNCONFIRMED");
                    }
                    billingCorrection = correction.correctionNumber() != null ? correction.correctionNumber() : "CORR-" + correction.id();
                } else {
                    billingCorrection = "NOT_INVOICED";
                }
                recordedCorrectionOutcomes.put(request.id(), billingCorrection);
            }

            // 3. Payment Refund in payment-service — requires confirmed SUCCEEDED refund (R02)
            String refundKey = "return-ref-v1-" + request.id();
            var ref = payment.refund(p.id(), returnAmount, refundKey, "CUSTOMER_REQUEST");
            if (ref == null || ref.id() == null || !"SUCCEEDED".equals(ref.status())) {
                throw ReturnIntent.conflict("RETURN_PAYMENT_REFUND_NOT_SETTLED");
            }
            if (ref.amount() != null && ref.amount().compareTo(returnAmount) != 0) {
                throw ReturnIntent.conflict("RETURN_PAYMENT_REFUND_AMOUNT_MISMATCH");
            }
            String paymentRefund = ref.providerRefundId() != null && !ref.providerRefundId().isBlank()
                    ? ref.providerRefundId()
                    : "REF-" + ref.id();

            String summary = String.format("Restocked: %d lines; InvoiceCorrection: %s; PaymentRefund: %s",
                    restockReceipts.size(), billingCorrection, paymentRefund);
            return new Outcome(restockReceipts, billingCorrection, paymentRefund, summary);
        } finally {
            inFlightCases.remove(request.id());
        }
    }

    private BigDecimal calculateVerifiedRefundAmount(ReturnResponse request, ReturnOrderClient.Snapshot order) {
        if (order == null || order.totalPrice() == null || order.totalPrice().compareTo(BigDecimal.ZERO) <= 0
                || order.items() == null || order.items().isEmpty()
                || request.items() == null || request.items().isEmpty()) {
            throw ReturnIntent.conflict("RETURN_ORDER_SETTLEMENT_AMOUNTS_MISSING");
        }
        Map<Long, ReturnOrderClient.Line> orderLines = new HashMap<>();
        BigDecimal allOrderLinesGross = BigDecimal.ZERO;
        for (var line : order.items()) {
            if (line == null || line.id() == null || line.id() <= 0
                    || line.quantity() == null || line.quantity() <= 0
                    || line.unitPrice() == null || line.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw ReturnIntent.conflict("RETURN_ORDER_SETTLEMENT_AMOUNTS_MISSING");
            }
            if (orderLines.put(line.id(), line) != null) {
                throw ReturnIntent.conflict("RETURN_ORDER_ITEMS_UNVERIFIED");
            }
            allOrderLinesGross = allOrderLinesGross.add(
                    line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())).setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal expectedOrderTotal = order.totalPrice().setScale(2, RoundingMode.HALF_UP);
        if (allOrderLinesGross.compareTo(expectedOrderTotal) != 0) {
            throw ReturnIntent.conflict("RETURN_ORDER_DISCOUNT_OR_SHIPPING_REQUIRES_MANUAL_SETTLEMENT");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (var item : request.items()) {
            if (item == null || item.orderItemId() == null || item.quantity() <= 0) {
                throw ReturnIntent.conflict("RETURN_ITEMS_INVALID");
            }
            var orderLine = orderLines.get(item.orderItemId());
            if (orderLine == null || item.quantity() > orderLine.quantity()
                    || !Objects.equals(item.productId(), orderLine.productId())) {
                throw ReturnIntent.conflict("RETURN_QUANTITY_OR_ITEM_MISMATCH");
            }
            sum = sum.add(orderLine.unitPrice().multiply(BigDecimal.valueOf(item.quantity())).setScale(2, RoundingMode.HALF_UP));
        }
        if (sum.compareTo(BigDecimal.ZERO) <= 0 || sum.compareTo(expectedOrderTotal) > 0) {
            throw ReturnIntent.conflict("RETURN_INVALID_SETTLEMENT_AMOUNT");
        }
        return sum;
    }
}
