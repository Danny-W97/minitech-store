package org.demo.orderservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import org.demo.orderservice.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OrderDataExportPage(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                  List<OrderData> orders, boolean hasMore, String nextCursor, List<Omission> omissions) {
    @Schema(name = "OrderExportData")
    public record OrderData(Long orderId, BigDecimal totalPrice, OrderStatus status, String customerEmail,
                            Address deliveryAddress, String deliveryMethod, BigDecimal deliveryCost,
                            String promotionCode, BigDecimal discountAmount, String fulfillmentType,
                            LocalDate deliveryDate, String deliverySlot, String pickupLocation,
                            boolean invoiceRequested, Instant createdAt, Instant updatedAt, LegacyLine legacyLine) {}
    @Schema(name = "OrderExportLegacyLine")
    public record LegacyLine(Long productId, Integer quantity, BigDecimal unitPrice) {}
    @Schema(name = "OrderExportAddress")
    public record Address(String firstName, String lastName, String street, String postalCode,
                          String city, String country, String phone) {}
    @Schema(name = "OrderExportOmission")
    public record Omission(String scope, String status) {}
}
