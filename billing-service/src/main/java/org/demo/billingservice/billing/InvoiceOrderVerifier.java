package org.demo.billingservice.billing;

import org.demo.billingservice.web.InvoiceItemRequest;
import org.demo.billingservice.web.IssueInvoiceRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class InvoiceOrderVerifier {
    private final BillingOrderClient orders;
    public InvoiceOrderVerifier(BillingOrderClient orders) { this.orders = orders; }

    public IssueInvoiceRequest verify(IssueInvoiceRequest request) {
        var order = orders.get(request.orderId());
        if (order.lifecycleReviewRequired() || !Set.of("PAID", "SHIPPED", "COMPLETED").contains(order.status() == null ? "" : order.status())
                || order.paymentState()!=null && !"PAID".equals(order.paymentState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice requires a paid order");
        }
        if (!Objects.equals(request.customerId(), order.customerId())
                || !Objects.equals(request.customerEmail(), order.customerEmail())
                || (request.currency() != null && !request.currency().isBlank()
                    && !"PLN".equalsIgnoreCase(request.currency()))) {
            throw mismatch();
        }
        if (order.items() == null || order.items().isEmpty() || request.items() == null
                || order.items().size() != request.items().size()) throw mismatch();
        var submitted = new HashMap<Long, InvoiceItemRequest>();
        for (var item : request.items()) {
            if (item == null || submitted.put(item.productId(), item) != null) throw mismatch();
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var item : order.items()) {
            if (item == null || item.productId() == null || item.quantity() == null || item.quantity() < 1) throw mismatch();
            var claimed = submitted.remove(item.productId());
            if (claimed == null || !Objects.equals(claimed.sku(), item.sku())
                    || !Objects.equals(claimed.productName(), item.productName())
                    || !Objects.equals(claimed.quantity(), item.quantity())) throw mismatch();
            equalMoney(claimed.unitPrice(), item.unitPrice());
            equalMoney(claimed.totalPrice(), item.totalPrice());
            BigDecimal lineTotal = money(item.unitPrice()).multiply(BigDecimal.valueOf(item.quantity()));
            equalMoney(item.totalPrice(), lineTotal);
            subtotal = subtotal.add(lineTotal);
        }
        equalMoney(request.deliveryCost(), order.deliveryCost());
        equalMoney(request.discountAmount(), order.discountAmount());
        equalMoney(request.grossAmount(), order.totalPrice());
        equalMoney(order.totalPrice(), subtotal.add(money(order.deliveryCost())).subtract(money(order.discountAmount())));
        // Only the buyer's address/details come from the command; financial and owner data come from Order Service.
        List<InvoiceItemRequest> items = order.items().stream().map(item -> new InvoiceItemRequest(item.productId(),
                item.sku(), item.productName(), item.quantity(), money(item.unitPrice()), money(item.totalPrice()))).toList();
        return new IssueInvoiceRequest(order.id(), order.customerId(), order.customerEmail(), money(order.totalPrice()),
                "PLN", money(order.deliveryCost()), money(order.discountAmount()), items, request.billingData());
    }

    private static void equalMoney(BigDecimal claimed, BigDecimal expected) {
        if (money(claimed).compareTo(money(expected)) != 0) throw mismatch();
    }

    private static BigDecimal money(BigDecimal value) {
        try {
            if (value == null || value.signum() < 0) throw mismatch();
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) { throw mismatch(); }
    }

    private static ResponseStatusException mismatch() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice data does not match the order");
    }
}
