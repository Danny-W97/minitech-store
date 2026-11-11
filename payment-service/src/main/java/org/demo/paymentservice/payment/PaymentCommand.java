package org.demo.paymentservice.payment;

import org.demo.paymentservice.web.CreatePaymentRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Length framing distinguishes null, empty strings and field boundaries. Only a digest is persisted. */
final class PaymentCommand {
    private PaymentCommand() {}

    static String fingerprint(CreatePaymentRequest request, PaymentMethod method) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var data = request.billingData();
            Object[] fields = {request.orderId(), method.name(), request.simulateFailure(),
                    request.notificationRecipient(), request.customerId(), Boolean.TRUE.equals(request.invoiceRequested()),
                    data == null ? null : data.company(), data == null ? null : data.buyerName(),
                    data == null ? null : data.companyName(), data == null ? null : data.taxId(),
                    data == null ? null : data.street(), data == null ? null : data.postalCode(),
                    data == null ? null : data.city(), data == null ? null : data.country()};
            for (Object field : fields) {
                byte[] value = field == null ? new byte[0] : field.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(field == null ? -1 : value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
