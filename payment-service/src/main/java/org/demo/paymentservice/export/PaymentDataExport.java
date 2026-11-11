package org.demo.paymentservice.export;

import io.swagger.v3.oas.annotations.media.Schema;
import org.demo.paymentservice.payment.PaymentStatus;
import org.demo.paymentservice.payment.PaymentRefund;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PaymentDataExport(int schemaVersion, String scope, boolean complete, Instant generatedAt,
                                PaymentData payment, RefundData refund, List<Omission> omissions) {
    @Schema(name="PaymentExportData")
    public record PaymentData(Long paymentId, Long orderId, BigDecimal amount, String currency,
                              String paymentMethod, PaymentStatus status, Instant collectedAt,
                              Instant createdAt, Instant updatedAt) {}
    @Schema(name="PaymentExportRefund")
    public record RefundData(Long refundId, Long paymentId, Long orderId, BigDecimal amount, String currency,
                             PaymentRefund.Status status, Instant submittedAt, Instant createdAt, Instant updatedAt) {}
    @Schema(name="PaymentExportOmission")
    public record Omission(String scope, String status) {}
}
