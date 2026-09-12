package org.demo.billingservice.messaging;

import org.demo.billingservice.billing.Invoice;
import org.demo.billingservice.billing.InvoiceCorrection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InvoiceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final String streamName;

    public InvoiceEventPublisher(
            StringRedisTemplate redisTemplate,
            @Value("${messaging.invoice-stream:invoice-events}") String streamName
    ) {
        this.redisTemplate = redisTemplate;
        this.streamName = streamName;
    }

    public boolean publishInvoiceIssued(Invoice invoice) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "INVOICE_ISSUED");
        fields.put("invoiceId", String.valueOf(invoice.getId()));
        fields.put("orderId", String.valueOf(invoice.getOrderId()));
        fields.put("invoiceNumber", invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : "");
        fields.put("grossAmount", invoice.getGrossAmount() != null ? invoice.getGrossAmount().toPlainString() : "0.00");
        fields.put("currency", invoice.getCurrency() != null ? invoice.getCurrency() : "PLN");
        if (invoice.getCustomerEmail() != null) {
            fields.put("customerEmail", invoice.getCustomerEmail());
        }
        if (invoice.getBuyerName() != null) {
            fields.put("buyerName", invoice.getBuyerName());
        }
        if (invoice.getCompanyName() != null) {
            fields.put("companyName", invoice.getCompanyName());
        }
        if (invoice.getCreatedAt() != null) {
            fields.put("createdAt", invoice.getCreatedAt().toString());
        }

        try {
            redisTemplate.opsForStream().add(
                    StreamRecords.newRecord().ofMap(fields).withStreamKey(streamName)
            );
            log.info("Published INVOICE_ISSUED event to stream '{}' for invoice {} (order #{})",
                    streamName, invoice.getInvoiceNumber(), invoice.getOrderId());
            return true;
        } catch (DataAccessException | IllegalStateException exception) {
            log.warn("Could not publish INVOICE_ISSUED event to stream '{}': {}", streamName, exception.getMessage());
            return false;
        }
    }

    public boolean publishInvoiceCorrected(InvoiceCorrection correction) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "INVOICE_CORRECTED");
        fields.put("correctionId", String.valueOf(correction.getId()));
        fields.put("invoiceId", String.valueOf(correction.getInvoice().getId()));
        fields.put("orderId", String.valueOf(correction.getOrderId()));
        fields.put("correctionNumber", correction.getCorrectionNumber());
        fields.put("originalInvoiceNumber", correction.getInvoice() != null && correction.getInvoice().getInvoiceNumber() != null ? correction.getInvoice().getInvoiceNumber() : "");
        fields.put("reason", correction.getReason());
        fields.put("correctionGross", correction.getCorrectionGross().toPlainString());
        fields.put("finalGross", correction.getFinalGross().toPlainString());
        if (correction.getCreatedAt() != null) {
            fields.put("createdAt", correction.getCreatedAt().toString());
        }

        try {
            redisTemplate.opsForStream().add(
                    StreamRecords.newRecord().ofMap(fields).withStreamKey(streamName)
            );
            log.info("Published INVOICE_CORRECTED event to stream '{}' for correction {} (order #{})",
                    streamName, correction.getCorrectionNumber(), correction.getOrderId());
            return true;
        } catch (DataAccessException | IllegalStateException exception) {
            log.warn("Could not publish INVOICE_CORRECTED event to stream '{}': {}", streamName, exception.getMessage());
            return false;
        }
    }
}
