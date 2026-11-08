package org.demo.billingservice.messaging;

import org.demo.billingservice.billing.Invoice;
import org.demo.billingservice.billing.InvoiceCorrection;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InvoiceEventPublisher {

    private final InvoiceOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired private org.demo.platformsecurity.privacy.PrivacySql privacy;
    public InvoiceEventPublisher(InvoiceOutboxRepository outbox) { this.outbox=outbox; }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
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

        save("invoice-issued-"+invoice.getId(),fields);
        return true;
    }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
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
        fields.put("balanceModel",correction.getLedgerVersion()==null ? "LEGACY_BASE_DELTA" : "CUMULATIVE_DELTA");
        if(correction.getLedgerVersion()!=null) fields.put("balanceVersion",correction.getLedgerVersion().toString());
        if (correction.getCreatedAt() != null) {
            fields.put("createdAt", correction.getCreatedAt().toString());
        }

        save("invoice-corrected-"+correction.getId(),fields);
        return true;
    }

    private void save(String id,Map<String,String> fields){
        boolean sealed=privacy!=null&&privacy.orderSealed(Long.parseLong(fields.get("orderId")));
        if(sealed){fields.keySet().removeAll(java.util.Set.of("customerEmail","buyerName","companyName","reason"));fields.put("privacySuppressed","true");}
        var event=new InvoiceOutboxEvent(id,fields);if(sealed)event.privacySafe();outbox.save(event);
    }
}
