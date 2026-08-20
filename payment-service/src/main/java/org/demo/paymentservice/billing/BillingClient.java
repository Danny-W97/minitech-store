package org.demo.paymentservice.billing;

import org.demo.paymentservice.config.ResilientHttpExecutor;
import org.demo.paymentservice.order.OrderResponse;
import org.demo.paymentservice.web.CreatePaymentRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;

@Component
public class BillingClient {
    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;
    public BillingClient(RestClient.Builder builder,
                         @Value("${services.billing.base-url:http://localhost:18095}") String baseUrl,
                         ResilientHttpExecutor resilientHttpExecutor) {
        this.restClient = builder.baseUrl(baseUrl).build(); this.resilientHttpExecutor = resilientHttpExecutor;
    }
    public Long issue(CreatePaymentRequest request, OrderResponse order) {
        if (!Boolean.TRUE.equals(request.invoiceRequested()) || request.billingData() == null) return null;
        BillingDataRequest data = request.billingData();
        List<InvoiceItemPayload> items = order.items() == null ? List.of() : order.items().stream()
                .map(item -> new InvoiceItemPayload(item.productId(), item.sku(), item.productName(), item.quantity(),
                        item.unitPrice(), item.totalPrice()))
                .toList();
        if (items.isEmpty()) throw new BillingServiceException("Order has no line items for the invoice", null);
        try {
            InvoiceResponse invoice = resilientHttpExecutor.execute("billing", () -> restClient.post().uri("/invoices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new IssueInvoicePayload(order.id(), request.customerId(), request.notificationRecipient(), order.totalPrice(), "PLN",
                            amount(order.deliveryCost()), amount(order.discountAmount()), items, data))
                    .retrieve().body(InvoiceResponse.class));
            return invoice == null ? null : invoice.id();
        } catch (RestClientResponseException exception) {
            throw new BillingServiceException("Billing Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new BillingServiceException("Could not reach Billing Service", exception);
        }
    }
    private BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO.setScale(2) : value; }
    public record InvoiceResponse(Long id) {}
    private record IssueInvoicePayload(Long orderId, Long customerId, String customerEmail, BigDecimal grossAmount,
                                       String currency, BigDecimal deliveryCost, BigDecimal discountAmount,
                                       List<InvoiceItemPayload> items, BillingDataRequest billingData) {}
    private record InvoiceItemPayload(Long productId, String sku, String productName, Integer quantity,
                                      BigDecimal unitPrice, BigDecimal totalPrice) {}
}
