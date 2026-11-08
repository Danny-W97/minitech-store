package org.demo.billingservice.messaging;

import org.demo.platformevents.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@Service
public class PaymentEventInbox {
    private final EventReceiptRepository receipts;
    private final org.demo.billingservice.billing.InvoiceRepository invoices;
    private final org.springframework.jdbc.core.JdbcTemplate aliases;
    @org.springframework.beans.factory.annotation.Autowired private org.demo.platformsecurity.privacy.PrivacySql privacy;
    public PaymentEventInbox(EventReceiptRepository receipts,org.demo.billingservice.billing.InvoiceRepository invoices) { this(receipts,invoices,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentEventInbox(EventReceiptRepository receipts,org.demo.billingservice.billing.InvoiceRepository invoices,org.springframework.jdbc.core.JdbcTemplate aliases) { this.receipts=receipts;this.invoices=invoices;this.aliases=aliases; }
    @Transactional public void accept(String id,Map<String,String> fields) {
        var identity=EventIdentity.envelope(id,fields);
        if(identity.aliasId()!=null){var oldReader=receipts.findById(identity.aliasId());if(oldReader.isPresent()&&!oldReader.get().getPayloadHash().equals(EventIdentity.fingerprint(fields)))throw new IllegalArgumentException("Event ID was reused with different content");}
        org.demo.platformevents.EventAliases.bind(aliases,identity);id=identity.canonicalId();String hash=identity.payloadHash();var previous=receipts.findById(id);
        if(previous.isPresent()) {
            if(!previous.get().getPayloadHash().equals(hash)) throw new IllegalArgumentException("Event ID was reused with different content");saveAlias(previous.get(),identity,fields);return;
        }
        var event=PaymentEventData.parse(fields);
        if(privacy!=null&&privacy.orderSealed(event.orderId())){var suppressed=new EventReceipt(id,hash,"SUPPRESSED",null,null,null).forOrder(event.orderId());receipts.saveAndFlush(suppressed);saveAlias(suppressed,identity,fields);return;}

        // Payment events remain observations; invoice issuance still verifies a paid order over HTTP.
        if(event.type().equals("PAYMENT_SUCCEEDED")) invoices.findByOrderId(event.orderId());
        var accepted=new EventReceipt(id,hash,"ACCEPTED",event.orderId(),null,null).forOrder(event.orderId());receipts.saveAndFlush(accepted);saveAlias(accepted,identity,fields);
    }

    private void saveAlias(EventReceipt canonical,EventIdentity.Envelope identity,Map<String,String> fields){
        if(identity.aliasId()!=null&&!receipts.existsById(identity.aliasId()))receipts.saveAndFlush(canonical.alias(identity.aliasId(),EventIdentity.fingerprint(fields)));
    }
}
